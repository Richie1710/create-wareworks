package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.FluidRowState;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The fluid half of a warehouse terminal's screen (M31, issue #21, {@code docs/warehouse-system.md} §3.9.6): one cell
 * per fluid the bays hold, and one order line per open fill request of this terminal.
 * <p>
 * It travels whole in {@code network.TerminalStockPayload} whenever it changed, and is compared by value, so a
 * warehouse without fluid bays — and one whose fluid did not move — costs one zero byte per stock push. Reading is
 * bounded at {@value #MAX_ROWS} cells and {@value #MAX_FILLS} lines, whatever the length prefixes claim.
 *
 * @param rows  one cell per fluid, in {@link FluidKey#ORDER}
 * @param fills the open fill requests of this terminal, oldest first
 */
public record TerminalFluids(List<Row> rows, List<FillLine> fills) {
    /** Most fluid cells a screen is told about; far above the handful of fluids a warehouse holds. */
    public static final int MAX_ROWS = 32;
    /** Most fill lines; a terminal has at most {@code maxOpenRequestsPerOutput} open requests anyway. */
    public static final int MAX_FILLS = 16;

    /** No fluid at all: the shape every warehouse had before M31. */
    public static final TerminalFluids NONE = new TerminalFluids(List.of(), List.of());

    public TerminalFluids {
        rows = bounded(rows, MAX_ROWS);
        fills = bounded(fills, MAX_FILLS);
    }

    /** Whether there is nothing to show. */
    public boolean isEmpty() {
        return rows.isEmpty() && fills.isEmpty();
    }

    /**
     * One fluid cell.
     *
     * @param fluid       the fluid
     * @param storedMb    what the bays hold of it
     * @param availableMb what a new request may still claim: stored minus what open fill requests still need
     * @param servableMb  what a request for all of {@code availableMb} would serve with the empty containers in stock,
     *                    i.e. what a Ctrl-click asks for; computed by the server, never by the screen
     * @param state       what the cell says about asking for it
     * @param container   the largest empty container in stock it fills, shown in the tooltip
     * @param containerMb what {@code container} holds of it, in mB; 0 without one
     * @param makeable    the empty container a production pattern of the aisle makes for it, if there is none in stock
     */
    public record Row(FluidKey fluid, long storedMb, long availableMb, long servableMb, FluidRowState state,
                      Optional<ItemKey> container, int containerMb, Optional<ItemKey> makeable) {
        public Row {
            Objects.requireNonNull(fluid, "fluid");
            storedMb = Math.max(0L, storedMb);
            availableMb = Math.max(0L, availableMb);
            servableMb = Math.max(0L, servableMb);
            Objects.requireNonNull(state, "state");
            container = container == null ? Optional.empty() : container;
            containerMb = container.isEmpty() ? 0 : Math.max(0, containerMb);
            makeable = makeable == null ? Optional.empty() : makeable;
        }

        void write(RegistryFriendlyByteBuf buffer) {
            FluidKey.STREAM_CODEC.encode(buffer, fluid);
            buffer.writeVarLong(storedMb);
            buffer.writeVarLong(availableMb);
            buffer.writeVarLong(servableMb);
            // By name: a state this build does not know reads as OK instead of as a shifted one.
            buffer.writeUtf(state.name(), MAX_NAME);
            buffer.writeOptional(container, (out, key) -> ItemKey.STREAM_CODEC.encode((RegistryFriendlyByteBuf) out,
                    key));
            buffer.writeVarInt(containerMb);
            buffer.writeOptional(makeable, (out, key) -> ItemKey.STREAM_CODEC.encode((RegistryFriendlyByteBuf) out,
                    key));
        }

        static Row read(RegistryFriendlyByteBuf buffer) {
            FluidKey fluid = FluidKey.STREAM_CODEC.decode(buffer);
            long stored = buffer.readVarLong();
            long available = buffer.readVarLong();
            long servable = buffer.readVarLong();
            FluidRowState state = FluidRowState.byName(buffer.readUtf(MAX_NAME));
            Optional<ItemKey> container = buffer.readOptional(in -> ItemKey.STREAM_CODEC.decode(
                    (RegistryFriendlyByteBuf) in));
            int containerMb = buffer.readVarInt();
            Optional<ItemKey> makeable = buffer.readOptional(in -> ItemKey.STREAM_CODEC.decode(
                    (RegistryFriendlyByteBuf) in));
            return new Row(fluid, stored, available, servable, state, container, containerMb, makeable);
        }
    }

    /**
     * One open fill request of this terminal, as its order line shows it: "Lava Bucket x3 — Filling from Lava", with the
     * cancel mark that gives it up.
     *
     * @param id        the request; the cancel mark sends it back, and the server checks it is a fill request of this
     *                  terminal
     * @param filled    the filled container it asks for
     * @param fluid     the fluid it is filled from
     * @param remaining containers still to come
     */
    public record FillLine(UUID id, ItemKey filled, FluidKey fluid, int remaining) {
        public FillLine {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(filled, "filled");
            Objects.requireNonNull(fluid, "fluid");
            remaining = Math.max(0, remaining);
        }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            ItemKey.STREAM_CODEC.encode(buffer, filled);
            FluidKey.STREAM_CODEC.encode(buffer, fluid);
            buffer.writeVarInt(remaining);
        }

        static FillLine read(RegistryFriendlyByteBuf buffer) {
            return new FillLine(buffer.readUUID(), ItemKey.STREAM_CODEC.decode(buffer),
                    FluidKey.STREAM_CODEC.decode(buffer), buffer.readVarInt());
        }
    }

    /** Longest state name a payload may claim. */
    private static final int MAX_NAME = 64;

    /** Writes both lists, each with its length first. */
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(rows.size());
        for (Row row : rows)
            row.write(buffer);
        buffer.writeVarInt(fills.size());
        for (FillLine fill : fills)
            fill.write(buffer);
    }

    /** Reads what {@link #write} wrote, bounded at {@value #MAX_ROWS} cells and {@value #MAX_FILLS} lines. */
    public static TerminalFluids read(RegistryFriendlyByteBuf buffer) {
        int rowCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_ROWS);
        List<Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++)
            rows.add(Row.read(buffer));
        int fillCount = Math.min(Math.max(0, buffer.readVarInt()), MAX_FILLS);
        List<FillLine> fills = new ArrayList<>(fillCount);
        for (int i = 0; i < fillCount; i++)
            fills.add(FillLine.read(buffer));
        return new TerminalFluids(rows, fills);
    }

    private static <T> List<T> bounded(List<T> list, int max) {
        if (list == null || list.isEmpty())
            return List.of();
        return List.copyOf(list.size() <= max ? list : list.subList(0, max));
    }
}
