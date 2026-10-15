package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crafting.IngredientPicker;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Client → server: "lay this recipe into the crafting panel of the terminal I have open" — what a recipe viewer's
 * transfer button sends (issue #24, slice 4, {@code docs/warehouse-system.md} §3.4.5).
 * <p>
 * <b>Candidates, not a grid.</b> A recipe names an ingredient per cell, and an ingredient accepts several items
 * ({@code #planks}); a grid cell is one exact item. So each of the nine cells carries the items its ingredient accepts,
 * in the recipe's own order, and the <b>server</b> picks one per cell from what the terminal's buffer, the aisle's
 * stock and its production stations say ({@link IngredientPicker}). The picked grid is then checked against the
 * server's own recipe manager, exactly as the panel's preview is; only a grid that matches a crafting recipe is laid.
 * <p>
 * <b>Nothing in it is trusted.</b> The {@code containerId} must be the menu the player really has open and
 * {@link #terminal()} that menu's terminal; the terminal re-validates reach, and refuses while a craft order locks
 * the grid. The hint only says which recipe the viewer showed: it is tried first, and a grid that matches no recipe is
 * refused whatever it names. Laying moves no item and orders nothing — it only gives the panel a grid.
 * <p>
 * <b>Strict shape.</b> At most {@value #MAX_BYTES} bytes, exactly {@value CraftGrid#SIZE} cells and at most
 * {@value IngredientPicker#MAX_CANDIDATES} candidates per cell; a payload that claims anything else fails to decode,
 * like a vanilla list over its bound, and never reaches the server's handler. A candidate is an {@link ItemKey}: an
 * item with its components and without a count, so it is never air (the item codec refuses an empty stack) and always
 * one item. The record itself does not enforce the bounds, so a payload built in-process is checked again by the
 * server ({@link #wellFormed()}).
 * <p>
 * <b>What a lay may cost the server.</b> The candidates are a client's choice, components and hash codes included. The
 * byte bound keeps what the server thread hashes and compares per lay small, however large a client makes the
 * components; {@link IngredientPicker} keeps at most {@value IngredientPicker#MAX_SAME_HASH} distinct candidates per
 * hash code, so keys made to share one cost linear work, not quadratic; and the menu takes one lay per tick
 * ({@code WarehouseTerminalMenu#submitCraftLay}). A client sends what fits ({@link #fitted}): an honest lay of plain
 * items is a few kilobytes, and one that does not fit loses the candidates at the end of its longest cells, never a
 * cell.
 *
 * @param containerId the menu the player has open
 * @param terminal    the position of that menu's terminal
 * @param hint        the recipe the viewer showed, as the viewer named it; a hint only
 * @param cells       per cell, row-major, the items its ingredient accepts in the recipe's order; an empty list is an
 *                    empty cell
 * @param foldOut     whether the panel should fold out once the grid is laid; the server hands it back with the laid
 *                    grid ({@link TerminalCraftViewPayload.Lay#foldOut()})
 */
public record TerminalCraftLayPayload(int containerId, BlockPos terminal, Optional<ResourceLocation> hint,
                                      List<List<ItemKey>> cells, boolean foldOut) implements CustomPacketPayload {
    /** Most candidates one cell may carry: what the picker considers, and what a client may send for it. */
    public static final int MAX_CANDIDATES = IngredientPicker.MAX_CANDIDATES;
    /**
     * Most bytes a lay may take after its type id: the most vanilla lets any serverbound custom payload carry that it
     * does not know ({@code ServerboundCustomPayloadPacket}). Nine cells of {@value #MAX_CANDIDATES} plain items take
     * about three kilobytes.
     */
    public static final int MAX_BYTES = 32767;

    public static final CustomPacketPayload.Type<TerminalCraftLayPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft_lay"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftLayPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftLayPayload::write, TerminalCraftLayPayload::decode);

    public TerminalCraftLayPayload {
        Objects.requireNonNull(terminal, "terminal");
        terminal = terminal.immutable();
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(cells, "cells");
        // Copies, so the payload cannot change after it was checked; a null cell or candidate is refused here.
        List<List<ItemKey>> copied = new ArrayList<>(cells.size());
        for (List<ItemKey> cell : cells)
            copied.add(List.copyOf(Objects.requireNonNull(cell, "cell")));
        cells = List.copyOf(copied);
    }

    /**
     * Whether the payload has the one shape the server accepts: exactly {@value CraftGrid#SIZE} cells, each with at
     * most {@value #MAX_CANDIDATES} candidates. A decoded payload always has it; one built in-process may not.
     */
    public boolean wellFormed() {
        return wellFormed(cells);
    }

    /**
     * The lay a client sends for {@code cells}: the cells as they are when the payload fits {@value #MAX_BYTES} bytes,
     * otherwise every cell cut to its first half, then its first quarter, and so on, until it fits — a cell's first
     * candidates are the recipe's own order, and no cell loses its last one. Empty when even one candidate per cell
     * does not fit, or when the cells do not have the one shape a lay has ({@link #wellFormed(List)}).
     *
     * @param registries     the registries of the connection the payload goes through
     * @param connectionType that connection's type, which its codecs may depend on
     */
    public static Optional<TerminalCraftLayPayload> fitted(int containerId, BlockPos terminal,
            Optional<ResourceLocation> hint, List<? extends List<ItemKey>> cells, boolean foldOut,
            RegistryAccess registries, ConnectionType connectionType) {
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(connectionType, "connectionType");
        if (!wellFormed(cells))
            return Optional.empty();
        int limit = 0;
        for (List<ItemKey> cell : cells)
            limit = Math.max(limit, cell.size());
        while (true) {
            List<List<ItemKey>> cut = new ArrayList<>(cells.size());
            for (List<ItemKey> cell : cells)
                cut.add(cell.subList(0, Math.min(cell.size(), limit)));
            TerminalCraftLayPayload payload = new TerminalCraftLayPayload(containerId, terminal, hint, cut, foldOut);
            if (payload.encodedSize(registries, connectionType) <= MAX_BYTES)
                return Optional.of(payload);
            if (limit <= 1)
                return Optional.empty();
            limit = (limit + 1) / 2;
        }
    }

    /**
     * How many bytes the payload takes on a connection with these registries and this type, after its type id:
     * exactly what {@link #decode} reads, and what {@value #MAX_BYTES} bounds.
     */
    public int encodedSize(RegistryAccess registries, ConnectionType connectionType) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, connectionType);
        try {
            writeBody(buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    /** {@link #wellFormed()} for a list of cells that is not a payload yet. */
    public static boolean wellFormed(List<? extends List<ItemKey>> cells) {
        if (cells == null || cells.size() != CraftGrid.SIZE)
            return false;
        for (List<ItemKey> cell : cells) {
            if (cell == null || cell.size() > MAX_CANDIDATES)
                return false;
            // Walked rather than asked with contains(null), which an immutable list answers with an exception.
            for (ItemKey candidate : cell) {
                if (candidate == null)
                    return false;
            }
        }
        return true;
    }

    /**
     * Reads what {@link #write} wrote, and refuses every other shape: more than {@value #MAX_BYTES} bytes throws before
     * anything is read, a cell count other than {@value CraftGrid#SIZE} or a candidate count outside
     * {@code 0..}{@value #MAX_CANDIDATES} throws before anything more is read, as a vanilla list codec over its bound
     * does, and an empty item throws inside the item codec. The payload then never reaches the handler.
     *
     * @throws DecoderException for any other shape
     */
    private static TerminalCraftLayPayload decode(RegistryFriendlyByteBuf buffer) {
        // Everything after the payload's type id is this payload: a packet frame carries one packet.
        if (buffer.readableBytes() > MAX_BYTES)
            throw new DecoderException("A recipe is laid in at most " + MAX_BYTES + " bytes, not "
                    + buffer.readableBytes());
        int containerId = buffer.readVarInt();
        BlockPos terminal = buffer.readBlockPos();
        Optional<ResourceLocation> hint = TerminalCraftPayload.readRecipeId(buffer);
        int count = buffer.readVarInt();
        if (count != CraftGrid.SIZE)
            throw new DecoderException("A recipe is laid into " + CraftGrid.SIZE + " cells, not " + count);
        List<List<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            int candidates = buffer.readVarInt();
            if (candidates < 0 || candidates > MAX_CANDIDATES)
                throw new DecoderException("Cell " + cell + " claims " + candidates + " candidates, at most "
                        + MAX_CANDIDATES + " are allowed");
            List<ItemKey> keys = new ArrayList<>(candidates);
            for (int i = 0; i < candidates; i++)
                keys.add(ItemKey.STREAM_CODEC.decode(buffer));
            cells.add(keys);
        }
        boolean foldOut = buffer.readBoolean();
        return new TerminalCraftLayPayload(containerId, terminal, hint, cells, foldOut);
    }

    /**
     * Writes the payload; one of another shape, or larger than {@value #MAX_BYTES} bytes, is never sent
     * ({@link #wellFormed()}, {@link #fitted}), so a caller that builds one is told at once rather than by a server
     * that drops the connection.
     *
     * @throws EncoderException when the payload is not well-formed or too large
     */
    private void write(RegistryFriendlyByteBuf buffer) {
        if (!wellFormed())
            throw new EncoderException("A recipe is laid as " + CraftGrid.SIZE + " cells of at most " + MAX_CANDIDATES
                    + " candidates each");
        int start = buffer.writerIndex();
        writeBody(buffer);
        int written = buffer.writerIndex() - start;
        if (written > MAX_BYTES)
            throw new EncoderException("A recipe is laid in at most " + MAX_BYTES + " bytes, not " + written);
    }

    private void writeBody(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBlockPos(terminal);
        TerminalCraftPayload.writeRecipeId(buffer, hint);
        buffer.writeVarInt(cells.size());
        for (List<ItemKey> cell : cells) {
            buffer.writeVarInt(cell.size());
            for (ItemKey key : cell)
                ItemKey.STREAM_CODEC.encode(buffer, key);
        }
        buffer.writeBoolean(foldOut);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
