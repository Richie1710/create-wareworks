package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.core.terminal.TerminalUsage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the order this player last chose and how often they have asked for each item type, for the one
 * player whose terminal screen is open (M24, issue #17, ADR-037).
 * <p>
 * <b>Why the client is sent numbers and not a sorted list.</b> The screen already holds the whole bounded stock list,
 * because it has to: the search box narrows it on every keystroke and the grid pages through it on every scroll, and
 * neither may cost a round trip. Sorting on the server would therefore mean sending the list again for every keystroke
 * — or sending a rank per item, which is this payload with more bytes. What must <b>not</b> be the client's is the
 * data, and it is not: the counts are counted, bounded, evicted, faded and saved on the server
 * ({@code content.station.TerminalPreferences}), the chosen order is saved there too, and the screen may only read
 * them. Both sides then produce the same list because the comparator is the same pure
 * {@link TerminalSort#comparator(dev.wareworks.core.terminal.TerminalUsageCounts)} code.
 * <p>
 * It is sent when a screen opens (with the first, resetting stock page) and whenever the store changed afterwards — a
 * request the player made, or the fade that request triggered — so the list reorders while the screen is open without
 * anything being sent while nothing changes.
 * <p>
 * <b>Counts only, no recency.</b> A screen sorts by the count and breaks ties by the amounts a player can see
 * ({@link TerminalSort#USED}); the recency stamp exists for eviction and lives only in the save
 * ({@link TerminalUsage.Entry}).
 * <p>
 * Reading is bounded: at most {@link TerminalUsage#MAX_CAPACITY} entries are decoded, whatever the length prefix
 * claims, which is also the hard cap of the store that writes them.
 *
 * @param containerId the menu the payload belongs to; the screen ignores a payload for another menu
 * @param sort        the order the server has stored for this player, by {@link TerminalSort#name()}
 * @param entries     item types with a count, strongest first; empty for a player with no history
 */
public record TerminalUsagePayload(int containerId, TerminalSort sort, List<Entry> entries)
        implements CustomPacketPayload {
    /** One remembered item type on the wire: the key and how often it was asked for. */
    public record Entry(ItemKey key, int count) {
        public Entry {
            Objects.requireNonNull(key, "key");
            count = Math.max(0, Math.min(count, TerminalUsage.MAX_COUNT));
        }
    }

    /** Most entries in one payload, which is the store's own hard cap. */
    public static final int MAX_ENTRIES = TerminalUsage.MAX_CAPACITY;

    public static final CustomPacketPayload.Type<TerminalUsagePayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_usage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalUsagePayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalUsagePayload::write, TerminalUsagePayload::new);

    public TerminalUsagePayload {
        Objects.requireNonNull(entries, "entries");
        if (sort == null)
            sort = TerminalSort.DEFAULT;
        entries = List.copyOf(entries.size() <= MAX_ENTRIES ? entries : entries.subList(0, MAX_ENTRIES));
    }

    /** The payload for what the server has stored, dropping the recency stamps the client does not need. */
    public static TerminalUsagePayload of(int containerId, TerminalSort sort,
            List<TerminalUsage.Entry<ItemKey>> counts) {
        List<Entry> entries = new ArrayList<>(Math.min(counts.size(), MAX_ENTRIES));
        for (TerminalUsage.Entry<ItemKey> entry : counts) {
            if (entries.size() >= MAX_ENTRIES)
                break;
            entries.add(new Entry(entry.key(), entry.count()));
        }
        return new TerminalUsagePayload(containerId, sort, entries);
    }

    /** The store these entries describe, for the screen's model ({@code StockListModel#setUsage}). */
    public TerminalUsage<ItemKey> toUsage() {
        List<TerminalUsage.Entry<ItemKey>> counts = new ArrayList<>(entries.size());
        for (Entry entry : entries)
            counts.add(new TerminalUsage.Entry<>(entry.key(), entry.count()));
        return TerminalUsage.ofEntries(counts);
    }

    private TerminalUsagePayload(RegistryFriendlyByteBuf buffer) {
        // The order travels by name, so TerminalSort's declaration order stays a presentation decision and a name this
        // build does not know reads as the default rather than as a shifted constant.
        this(buffer.readVarInt(), TerminalSort.byName(buffer.readUtf()), readEntries(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUtf(sort.name());
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            ItemKey.STREAM_CODEC.encode(buffer, entry.key());
            buffer.writeVarInt(entry.count());
        }
    }

    private static List<Entry> readEntries(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(Math.max(0, buffer.readVarInt()), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            entries.add(new Entry(ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarInt()));
        return entries;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
