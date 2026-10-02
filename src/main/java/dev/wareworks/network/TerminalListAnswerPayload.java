package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalListResult;
import dev.wareworks.core.terminal.ListOrderConfirmation;
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
 * no screen can know. Reading is bounded at {@value ListOrderConfirmation#MAX_NAMED} named entries whatever the length
 * prefix claims, and an unknown result reads as "nothing happened".
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
        buffer.writeVarLong(question.wanted());
        buffer.writeVarLong(question.serveable());
        buffer.writeVarLong(question.producing());
        buffer.writeVarLong(question.missing());
        buffer.writeVarInt(question.entriesServed());
        buffer.writeVarInt(question.entriesShort());
        buffer.writeVarInt(question.entriesProducing());
        buffer.writeVarInt(question.entriesImpossible());
        buffer.writeVarInt(question.entriesDropped());
        List<ListOrderConfirmation.Line<ItemKey>> named = question.named();
        buffer.writeVarInt(named.size());
        for (ListOrderConfirmation.Line<ItemKey> line : named) {
            ItemKey.STREAM_CODEC.encode(buffer, line.key());
            buffer.writeVarLong(line.wanted());
            buffer.writeVarLong(line.serveable());
            buffer.writeVarLong(line.producing());
            buffer.writeVarLong(line.missing());
        }
    }

    private static Optional<ListOrderConfirmation<ItemKey>> readQuestion(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean())
            return Optional.empty();
        long wanted = buffer.readVarLong();
        long serveable = buffer.readVarLong();
        long producing = buffer.readVarLong();
        long missing = buffer.readVarLong();
        int served = buffer.readVarInt();
        int shortOf = buffer.readVarInt();
        int produced = buffer.readVarInt();
        int impossible = buffer.readVarInt();
        int dropped = buffer.readVarInt();
        int count = Math.min(Math.max(0, buffer.readVarInt()), ListOrderConfirmation.MAX_NAMED);
        List<ListOrderConfirmation.Line<ItemKey>> named = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            named.add(new ListOrderConfirmation.Line<>(ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong()));
        return Optional.of(new ListOrderConfirmation<>(wanted, serveable, producing, missing, served, shortOf,
                produced, impossible, dropped, named));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
