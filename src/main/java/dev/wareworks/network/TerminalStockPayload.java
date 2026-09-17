package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.StockCount;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: what a warehouse terminal holds in stock, for the one player whose screen is open (ADR-019).
 * <p>
 * The first payload after a screen opened carries {@link #reset()} and the whole list, split into pages of at most
 * {@value #MAX_ENTRIES} entries; every later payload carries only the entries whose amounts changed, plus item types
 * that left the index with a total of 0 ({@code core.terminal.StockDiff}). A warehouse in which nothing moves therefore
 * costs no packet at all.
 * <p>
 * Reading is bounded: at most {@value #MAX_ENTRIES} entries are ever decoded, whatever the length prefix claims.
 *
 * @param containerId the menu the payload belongs to; the screen ignores a payload for another menu
 * @param reset       whether the client must drop its list before applying these entries
 * @param entries     new, changed or removed ({@code total == 0}) item types
 */
public record TerminalStockPayload(int containerId, boolean reset, List<StockCount<ItemKey>> entries)
        implements CustomPacketPayload {
    /** Most entries in one payload; the server splits a longer list into several. */
    public static final int MAX_ENTRIES = 64;

    public static final CustomPacketPayload.Type<TerminalStockPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalStockPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalStockPayload::write, TerminalStockPayload::new);

    public TerminalStockPayload {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries.size() <= MAX_ENTRIES ? entries : entries.subList(0, MAX_ENTRIES));
    }

    private TerminalStockPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readBoolean(), readEntries(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeBoolean(reset);
        buffer.writeVarInt(entries.size());
        for (StockCount<ItemKey> entry : entries) {
            ItemKey.STREAM_CODEC.encode(buffer, entry.key());
            buffer.writeVarLong(entry.total());
            buffer.writeVarLong(entry.available());
            buffer.writeBoolean(entry.producible());
            // How many of it could be made right now (M11, ADR-024): the number a "request everything" click uses, so
            // it has to travel with the entry rather than be guessed by the screen.
            buffer.writeVarLong(entry.producibleAmount());
        }
    }

    private static List<StockCount<ItemKey>> readEntries(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(Math.max(0, buffer.readVarInt()), MAX_ENTRIES);
        List<StockCount<ItemKey>> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
            long total = buffer.readVarLong();
            long available = buffer.readVarLong();
            boolean producible = buffer.readBoolean();
            entries.add(new StockCount<>(key, total, available, producible, buffer.readVarLong()));
        }
        return entries;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
