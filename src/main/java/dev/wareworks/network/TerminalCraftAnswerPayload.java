package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client, to the acting player only: what a craft click at the terminal became (M32, issue #24,
 * {@code docs/warehouse-system.md} §3.4.5) — one sentence for the status row.
 * <p>
 * The items themselves reach the client the ordinary way: the buffer and the player's inventory are slots of the open
 * menu, so vanilla's container sync shows the craft. This answer only says how the click ended
 * ({@link TerminalCraftOutcome}): {@link TerminalCraftResult#CRAFTED} with the result and how many items it came to, or
 * the refusal it stopped at, with the items it made before that and the item it names. The result travels by
 * <b>name</b>, so its declaration order stays free; a name this build does not know reads as
 * {@link TerminalCraftResult#NO_RECIPE}, a plain refusal, rather than as a shifted constant.
 * <p>
 * The craft fetch (slice 2 of issue #24) appends its own fields after these.
 *
 * @param containerId the menu the answer belongs to; the screen ignores one for another menu
 * @param result      how the click ended
 * @param crafted     how many items it made, also before a refusal
 * @param about       the item the sentence names, if any ({@link TerminalCraftOutcome#about()})
 */
public record TerminalCraftAnswerPayload(int containerId, TerminalCraftResult result, int crafted,
                                         Optional<ItemKey> about) implements CustomPacketPayload {
    /** Longest result name a payload may claim; the bound keeps a hostile one from claiming a megabyte. */
    private static final int MAX_RESULT_NAME = 32;

    public static final CustomPacketPayload.Type<TerminalCraftAnswerPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft_answer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftAnswerPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftAnswerPayload::write, TerminalCraftAnswerPayload::new);

    public TerminalCraftAnswerPayload {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(about, "about");
        crafted = Math.max(0, crafted);
    }

    /** The answer to a craft click in the menu {@code containerId}. */
    public static TerminalCraftAnswerPayload of(int containerId, TerminalCraftOutcome outcome) {
        return new TerminalCraftAnswerPayload(containerId, outcome.result(), outcome.crafted(), outcome.about());
    }

    private TerminalCraftAnswerPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(),
                TerminalCraftResult.byName(buffer.readUtf(MAX_RESULT_NAME)).orElse(TerminalCraftResult.NO_RECIPE),
                buffer.readVarInt(), buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                        : Optional.empty());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUtf(result.name(), MAX_RESULT_NAME);
        buffer.writeVarInt(crafted);
        buffer.writeBoolean(about.isPresent());
        about.ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
    }

    /** The outcome this answer reports, for the screen. */
    public TerminalCraftOutcome outcome() {
        return new TerminalCraftOutcome(result, crafted, about);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
