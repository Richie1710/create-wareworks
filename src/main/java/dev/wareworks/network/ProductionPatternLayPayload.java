package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.production.ProductionEntry;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Client → server: "lay this recipe into that pattern of the production station I have open", what a recipe viewer's
 * transfer button sends there (ADR-076). The sibling of {@link TerminalCraftLayPayload}, with the same bounds on the
 * same reasoning: each of the nine cells carries candidates, and the server picks one item per cell.
 * <p>
 * <b>Two kinds of recipe.</b> With {@code crafting} the cells are the recipe's own grid: the counts and the result are
 * ignored, and the server looks the picked grid up in its own crafting recipes, which also say the result. Without it
 * the recipe belongs to a machine the server knows nothing about, so {@code counts} and {@code result} are the client's
 * word. That trusts nothing a player's clicks could not set anyway: a pattern is ghost items, written item by item from
 * whatever the client names ({@link ProductionPatternPayload}), and it moves nothing.
 *
 * @param containerId the menu the player has open
 * @param pattern     the pattern slot to overwrite
 * @param crafting    whether the cells are a crafting grid the server checks
 * @param cells       per cell, row-major, the items it accepts in the recipe's order; an empty list is an empty cell
 * @param counts      per cell the items of one run, parallel to {@code cells}
 * @param result      what one run makes; empty only with {@code crafting}
 */
public record ProductionPatternLayPayload(int containerId, int pattern, boolean crafting, List<List<ItemKey>> cells,
                                          List<Integer> counts, Optional<ProductionEntry<ItemKey>> result)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProductionPatternLayPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("production_pattern_lay"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionPatternLayPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProductionPatternLayPayload::write, ProductionPatternLayPayload::decode);

    public ProductionPatternLayPayload {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(result, "result");
        List<List<ItemKey>> copied = new ArrayList<>(cells.size());
        for (List<ItemKey> cell : cells)
            copied.add(List.copyOf(Objects.requireNonNull(cell, "cell")));
        cells = List.copyOf(copied);
        counts = List.copyOf(counts);
    }

    /**
     * Whether the payload has the one shape the server accepts ({@link TerminalCraftLayPayload#wellFormed(List)}, one
     * count per cell, and a result unless the server finds it itself). A decoded payload always has it.
     */
    public boolean wellFormed() {
        return TerminalCraftLayPayload.wellFormed(cells) && counts.size() == cells.size()
                && (crafting || result.isPresent());
    }

    /**
     * The lay a client sends: the cells as they are when they fit {@value TerminalCraftLayPayload#MAX_BYTES} bytes,
     * otherwise cut the way {@link TerminalCraftLayPayload#fitted} cuts them. Empty when even one candidate per cell
     * does not fit, or for any other shape.
     */
    public static Optional<ProductionPatternLayPayload> fitted(int containerId, int pattern, boolean crafting,
            List<? extends List<ItemKey>> cells, List<Integer> counts, Optional<ProductionEntry<ItemKey>> result,
            RegistryAccess registries, ConnectionType connectionType) {
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(connectionType, "connectionType");
        if (!TerminalCraftLayPayload.wellFormed(cells))
            return Optional.empty();
        int limit = 0;
        for (List<ItemKey> cell : cells)
            limit = Math.max(limit, cell.size());
        while (true) {
            List<List<ItemKey>> cut = new ArrayList<>(cells.size());
            for (List<ItemKey> cell : cells)
                cut.add(cell.subList(0, Math.min(cell.size(), limit)));
            ProductionPatternLayPayload payload =
                    new ProductionPatternLayPayload(containerId, pattern, crafting, cut, counts, result);
            if (!payload.wellFormed())
                return Optional.empty();
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, connectionType);
            try {
                payload.writeBody(buffer);
                if (buffer.readableBytes() <= TerminalCraftLayPayload.MAX_BYTES)
                    return Optional.of(payload);
            } finally {
                buffer.release();
            }
            if (limit <= 1)
                return Optional.empty();
            limit = (limit + 1) / 2;
        }
    }

    /**
     * Reads what {@link #write} wrote and refuses every other shape before reading further, as
     * {@link TerminalCraftLayPayload} does; a count is clamped by the pattern's own rule on the way in.
     *
     * @throws DecoderException for any other shape
     */
    private static ProductionPatternLayPayload decode(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > TerminalCraftLayPayload.MAX_BYTES)
            throw new DecoderException("A recipe is laid in at most " + TerminalCraftLayPayload.MAX_BYTES
                    + " bytes, not " + buffer.readableBytes());
        int containerId = buffer.readVarInt();
        int pattern = buffer.readVarInt();
        boolean crafting = buffer.readBoolean();
        int size = buffer.readVarInt();
        if (size != CraftGrid.SIZE)
            throw new DecoderException("A recipe is laid into " + CraftGrid.SIZE
                    + " cells, not " + size);
        List<List<ItemKey>> cells = new ArrayList<>(size);
        List<Integer> counts = new ArrayList<>(size);
        for (int cell = 0; cell < size; cell++) {
            int candidates = buffer.readVarInt();
            if (candidates < 0 || candidates > TerminalCraftLayPayload.MAX_CANDIDATES)
                throw new DecoderException("Cell " + cell + " claims " + candidates + " candidates, at most "
                        + TerminalCraftLayPayload.MAX_CANDIDATES + " are allowed");
            List<ItemKey> keys = new ArrayList<>(candidates);
            for (int i = 0; i < candidates; i++)
                keys.add(ItemKey.STREAM_CODEC.decode(buffer));
            cells.add(keys);
            counts.add(ProductionEntry.clampCellCount(buffer.readVarInt()));
        }
        Optional<ProductionEntry<ItemKey>> result = Optional.empty();
        if (buffer.readBoolean()) {
            ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
            result = Optional.of(new ProductionEntry<>(key, ProductionEntry.clampCellCount(buffer.readVarInt())));
        }
        return new ProductionPatternLayPayload(containerId, pattern, crafting, cells, counts, result);
    }

    /** @throws EncoderException when the payload is not well-formed; {@link #fitted} never builds such a payload */
    private void write(RegistryFriendlyByteBuf buffer) {
        if (!wellFormed())
            throw new EncoderException("A recipe is laid as nine cells of bounded candidates with one count each");
        writeBody(buffer);
    }

    private void writeBody(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(pattern);
        buffer.writeBoolean(crafting);
        buffer.writeVarInt(cells.size());
        for (int cell = 0; cell < cells.size(); cell++) {
            buffer.writeVarInt(cells.get(cell).size());
            for (ItemKey key : cells.get(cell))
                ItemKey.STREAM_CODEC.encode(buffer, key);
            buffer.writeVarInt(counts.get(cell));
        }
        buffer.writeBoolean(result.isPresent());
        result.ifPresent(entry -> {
            ItemKey.STREAM_CODEC.encode(buffer, entry.key());
            buffer.writeVarInt(entry.count());
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
