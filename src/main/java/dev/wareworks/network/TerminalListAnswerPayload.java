package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.core.production.PlanRefusal;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: what a player's action on a terminal's clipboard order became (M23, issue #19,
 * {@code docs/warehouse-system.md} §3.4.4) — one sentence, and the <b>question</b> when the answer is
 * {@link TerminalListResult#ASKING}.
 * <p>
 * {@link TerminalListResult#ASKING} means that <b>nothing was started</b>: the numbers in it are what the whole list
 * would really cost against the warehouse as it is now, and the player decides. Their Yes comes back as a
 * {@link TerminalListActionPayload} naming those numbers, which the server <b>measures again</b> before it acts on it
 * — the M15/M20 round trip applied to a list rather than a second mechanism.
 * <p>
 * The numbers are the server's: what the racks can give and what the aisle's patterns could make right now are things
 * no screen can know. Since issue #36 (ADR-069, protocol {@code "17"}) the question is <b>one row per entry</b> — its
 * split, the planner's refusal by name with the item it names, and at most
 * {@value ListOrderConfirmation#MAX_LINE_MATERIALS} missing materials — and every total is derived from the rows on
 * the client, so none is sent. Reading is bounded: more than {@value ListOrderConfirmation#MAX_LINES} rows or more than
 * {@value ListOrderConfirmation#MAX_LINE_MATERIALS} materials in a row is a {@link DecoderException}, which keeps a
 * full list at about 100 KiB, far below the client-bound packet limit. An unknown result reads as "nothing happened",
 * an unknown refusal as no reason.
 *
 * @param containerId the menu the answer belongs to; the screen ignores one for another menu
 * @param result      what happened
 * @param question    what the list would cost, present exactly for {@link TerminalListResult#ASKING}
 */
public record TerminalListAnswerPayload(int containerId, TerminalListResult result,
                                        Optional<ListOrderConfirmation<ItemKey>> question)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalListAnswerPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_list_answer"));

    /** Bit of a line's flags byte: a refusal name follows. */
    private static final int FLAG_REFUSAL = 1;
    /** Bit of a line's flags byte: the item the refusal names follows. */
    private static final int FLAG_ABOUT = 2;
    /** Longest {@link PlanRefusal} name a payload may claim, as in {@link TerminalResultPayload}. */
    private static final int MAX_REFUSAL_NAME = 32;

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalListAnswerPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalListAnswerPayload::write, TerminalListAnswerPayload::new);

    public TerminalListAnswerPayload {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(question, "question");
    }

    private TerminalListAnswerPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), readResult(buffer), readQuestion(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        // By name, not by ordinal: a build that does not know a result reads it as "nothing happened" instead of as a
        // shifted one, so the declaration order of the enum stays free (the rule a StoppedProduct's cause follows).
        buffer.writeUtf(result.name());
        buffer.writeBoolean(question.isPresent());
        question.ifPresent(cost -> writeQuestion(buffer, cost));
    }

    private static TerminalListResult readResult(RegistryFriendlyByteBuf buffer) {
        return TerminalListResult.byName(buffer.readUtf()).orElse(TerminalListResult.NOT_RUNNING);
    }

    private static void writeQuestion(RegistryFriendlyByteBuf buffer, ListOrderConfirmation<ItemKey> question) {
        buffer.writeVarInt(question.entriesDropped());
        List<ListOrderConfirmation.Line<ItemKey>> lines = question.lines();
        buffer.writeVarInt(lines.size());
        for (ListOrderConfirmation.Line<ItemKey> line : lines) {
            buffer.writeVarInt(line.order());
            ItemKey.STREAM_CODEC.encode(buffer, line.key());
            buffer.writeVarLong(line.wanted());
            buffer.writeVarLong(line.fromStock());
            buffer.writeVarLong(line.produced());
            buffer.writeVarLong(line.made());
            // The missing part is derived from the other three on both sides, so it is not sent.
            int flags = (line.refusal().isPresent() ? FLAG_REFUSAL : 0) | (line.about().isPresent() ? FLAG_ABOUT : 0);
            buffer.writeByte(flags);
            // By name, like TerminalResultPayload's refusal: the declaration order of PlanRefusal stays free.
            line.refusal().ifPresent(refusal -> buffer.writeUtf(refusal.name(), MAX_REFUSAL_NAME));
            line.about().ifPresent(about -> ItemKey.STREAM_CODEC.encode(buffer, about));
            buffer.writeVarInt(line.materials().size());
            for (ListOrderConfirmation.Material<ItemKey> material : line.materials()) {
                ItemKey.STREAM_CODEC.encode(buffer, material.key());
                buffer.writeVarLong(material.amount());
            }
        }
    }

    private static Optional<ListOrderConfirmation<ItemKey>> readQuestion(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean())
            return Optional.empty();
        int dropped = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > ListOrderConfirmation.MAX_LINES)
            throw new DecoderException("A list question holds at most " + ListOrderConfirmation.MAX_LINES
                    + " lines, not " + count);
        List<ListOrderConfirmation.Line<ItemKey>> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int order = buffer.readVarInt();
            ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
            long wanted = buffer.readVarLong();
            long fromStock = buffer.readVarLong();
            long produced = buffer.readVarLong();
            long made = buffer.readVarLong();
            byte flags = buffer.readByte();
            Optional<PlanRefusal> refusal = (flags & FLAG_REFUSAL) != 0
                    ? refusalOf(buffer.readUtf(MAX_REFUSAL_NAME)) : Optional.empty();
            Optional<ItemKey> about = (flags & FLAG_ABOUT) != 0
                    ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty();
            int materialCount = buffer.readVarInt();
            if (materialCount < 0 || materialCount > ListOrderConfirmation.MAX_LINE_MATERIALS)
                throw new DecoderException("A list entry names at most " + ListOrderConfirmation.MAX_LINE_MATERIALS
                        + " materials, not " + materialCount);
            List<ListOrderConfirmation.Material<ItemKey>> materials = new ArrayList<>(materialCount);
            for (int m = 0; m < materialCount; m++)
                materials.add(new ListOrderConfirmation.Material<>(ItemKey.STREAM_CODEC.decode(buffer),
                        buffer.readVarLong()));
            // The record's own constructor clamps every amount and derives the missing part, so a crafted payload
            // cannot make a line claim more than it wants.
            lines.add(new ListOrderConfirmation.Line<>(order, key, wanted, fromStock, produced, made, 0L, refusal,
                    about, materials));
        }
        return Optional.of(new ListOrderConfirmation<>(lines, dropped));
    }

    /**
     * The refusal a name stands for; empty for an empty or unknown one, so a build that does not know a reason shows
     * the line without one instead of failing the packet — and the item behind it is still consumed.
     */
    private static Optional<PlanRefusal> refusalOf(String encoded) {
        if (encoded == null || encoded.isEmpty())
            return Optional.empty();
        for (PlanRefusal refusal : PlanRefusal.values()) {
            if (refusal.name().equals(encoded))
                return Optional.of(refusal);
        }
        return Optional.empty();
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
