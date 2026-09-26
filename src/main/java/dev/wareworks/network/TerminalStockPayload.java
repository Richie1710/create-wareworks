package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.StockRuleStatus;
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
            // What a stock rule says about the item (M15, issue #3), as "no rule" or a status ordinal plus one, and
            // the part of the available amount its reserve holds back from automation. An unruled item — every item
            // of a warehouse without stock keepers — costs the two zero bytes a varint and a varlong take.
            buffer.writeVarInt(entry.rule().map(status -> status.ordinal() + 1).orElse(0));
            if (entry.rule().isEmpty())
                continue; // an unruled item — every item of a warehouse without keepers — costs one zero byte
            buffer.writeVarLong(entry.ruleReserved());
            // The storage cap the screen needs to warn, before a click, that an order would bring in more than the
            // warehouse wants to hold (M15 part 2). Written as "cap + 1", so "no cap" is a single zero byte rather
            // than the ten a negative varlong takes.
            buffer.writeVarLong(entry.ruleMaximum() + 1L);
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
            long producibleAmount = buffer.readVarLong();
            // "No rule" and "a rule this build does not know" are two different wire forms: the first is one zero byte,
            // the second is a tag plus the two numbers the writer emitted after it. Branching on the tag rather than on
            // the decoded status is what keeps an unknown ordinal from shifting every following entry (M15 review fix).
            int tag = buffer.readVarInt();
            if (tag == 0) {
                entries.add(new StockCount<>(key, total, available, producible, producibleAmount));
                continue;
            }
            long ruleReserved = buffer.readVarLong();
            long ruleMaximum = buffer.readVarLong() - 1L;
            Optional<StockRuleStatus> rule = readRule(tag);
            entries.add(rule.isEmpty() ? new StockCount<>(key, total, available, producible, producibleAmount)
                    : new StockCount<>(key, total, available, producible, producibleAmount, rule, ruleReserved,
                            ruleMaximum));
        }
        return entries;
    }

    /**
     * The rule status of a <b>non-zero</b> tag: the ordinal plus one the writer emitted. An ordinal this build does not
     * know — a client and a server of different versions, which the handshake refuses, but a decoder never assumes —
     * reads as "no rule" instead of throwing. The caller has already consumed the two numbers that follow such a tag,
     * so the rest of the buffer stays in step whatever this answers.
     */
    private static Optional<StockRuleStatus> readRule(int encoded) {
        int ordinal = encoded - 1;
        StockRuleStatus[] values = StockRuleStatus.values();
        return ordinal < 0 || ordinal >= values.length ? Optional.empty() : Optional.of(values[ordinal]);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
