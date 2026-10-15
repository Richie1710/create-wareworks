package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalCraftOutcome;
import dev.wareworks.content.station.TerminalCraftResult;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.production.PlanRefusal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client, to the acting player only: what an action on the terminal's crafting panel became (M32, issue #24,
 * {@code docs/warehouse-system.md} §3.4.5) — a craft click, or the Fetch button and the craft order behind it — as one
 * sentence for the status row and, for a Fetch, the question.
 * <p>
 * The items themselves reach the client the ordinary way: the buffer and the player's inventory are slots of the open
 * menu, so vanilla's container sync shows a craft. This answer only says how the action ended
 * ({@link TerminalCraftOutcome}): {@link TerminalCraftResult#CRAFTED} with the result and how many items it came to,
 * or the refusal it stopped at, with the items it made before that and the item it names; for a Fetch, the result
 * with a number of crafts. The result travels by <b>name</b>, so its declaration order stays free; a name this build
 * does not know reads as {@link TerminalCraftResult#NO_RECIPE}, a plain refusal, rather than as a shifted constant.
 * <p>
 * <b>The Fetch's fields come last</b> (slice 2): the plan refusal behind an ingredient that falls short, by name, and
 * the measured question ({@link CraftFetchQuestion}) — present for a question and for the two measured refusals, and
 * then what the screen's dialog shows: whole crafts asked for, possible and ready, the three bounds, the production
 * total a Yes agrees to, at most {@value CraftFetchQuestion#MAX_NAMED} named ingredients whatever the length prefix
 * claims — each with what a chain would make of it — and, appended in slice 3, why no chain makes up for the
 * ingredient that binds, by name, with the item that reason names ({@link CraftFetchQuestion#chainRefusal()},
 * {@link CraftFetchQuestion#chainAbout()}). A craft click's answer costs two zero bytes for them.
 *
 * @param containerId the menu the answer belongs to; the screen ignores one for another menu
 * @param result      how the action ended
 * @param crafted     how many items a craft made, also before a refusal; for a Fetch, a number of crafts
 * @param about       the item the sentence names, if any ({@link TerminalCraftOutcome#about()})
 * @param refusal     why a production chain could not make up for the ingredient a Fetch falls short of;
 *                    {@link #about()} is then the item it names ({@link TerminalCraftOutcome#refusal()})
 * @param question    what a Fetch would really bring, measured on the server
 */
public record TerminalCraftAnswerPayload(int containerId, TerminalCraftResult result, int crafted,
                                         Optional<ItemKey> about, Optional<PlanRefusal> refusal,
                                         Optional<CraftFetchQuestion<ItemKey>> question)
        implements CustomPacketPayload {
    /** Longest result name a payload may claim; the bound keeps a hostile one from claiming a megabyte. */
    private static final int MAX_RESULT_NAME = 32;
    /** Longest plan refusal name a payload may claim, for the same reason. */
    private static final int MAX_REFUSAL_NAME = 32;

    public static final CustomPacketPayload.Type<TerminalCraftAnswerPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft_answer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftAnswerPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftAnswerPayload::write, TerminalCraftAnswerPayload::decode);

    public TerminalCraftAnswerPayload {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(about, "about");
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(question, "question");
        crafted = Math.max(0, crafted);
    }

    /** The answer to a craft click, which names no plan refusal and carries no question: the shape of slice 1. */
    public TerminalCraftAnswerPayload(int containerId, TerminalCraftResult result, int crafted,
            Optional<ItemKey> about) {
        this(containerId, result, crafted, about, Optional.empty(), Optional.empty());
    }

    /** The answer to an action in the menu {@code containerId}. */
    public static TerminalCraftAnswerPayload of(int containerId, TerminalCraftOutcome outcome) {
        return new TerminalCraftAnswerPayload(containerId, outcome.result(), outcome.crafted(), outcome.about(),
                outcome.refusal(), outcome.question());
    }

    private static TerminalCraftAnswerPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        TerminalCraftResult result = TerminalCraftResult.byName(buffer.readUtf(MAX_RESULT_NAME))
                .orElse(TerminalCraftResult.NO_RECIPE);
        int crafted = buffer.readVarInt();
        Optional<ItemKey> about = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                : Optional.empty();
        Optional<PlanRefusal> refusal = planRefusal(buffer.readUtf(MAX_REFUSAL_NAME));
        Optional<CraftFetchQuestion<ItemKey>> question = readQuestion(buffer);
        return new TerminalCraftAnswerPayload(containerId, result, crafted, about, refusal, question);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUtf(result.name(), MAX_RESULT_NAME);
        buffer.writeVarInt(crafted);
        buffer.writeBoolean(about.isPresent());
        about.ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
        // Appended in slice 2, by name like the result, so a later build may add plan refusals.
        buffer.writeUtf(refusal.map(PlanRefusal::name).orElse(""), MAX_REFUSAL_NAME);
        buffer.writeBoolean(question.isPresent());
        question.ifPresent(measured -> writeQuestion(buffer, measured));
    }

    private static void writeQuestion(RegistryFriendlyByteBuf buffer, CraftFetchQuestion<ItemKey> question) {
        buffer.writeVarInt(question.asked());
        buffer.writeVarInt(question.ready());
        buffer.writeVarInt(question.stockRuns());
        buffer.writeVarInt(question.madeRuns());
        buffer.writeVarInt(question.roomRuns());
        buffer.writeBoolean(question.about().isPresent());
        question.about().ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
        buffer.writeVarLong(question.producing());
        List<CraftFetchQuestion.Line<ItemKey>> named = question.named();
        buffer.writeVarInt(named.size());
        for (CraftFetchQuestion.Line<ItemKey> line : named) {
            ItemKey.STREAM_CODEC.encode(buffer, line.key());
            buffer.writeVarLong(line.needed());
            buffer.writeVarLong(line.inStock());
            buffer.writeVarLong(line.made());
        }
        // Appended in slice 3 (the chain offer): why no chain makes up for the bottleneck, by name like every refusal.
        buffer.writeUtf(question.chainRefusal().map(PlanRefusal::name).orElse(""), MAX_REFUSAL_NAME);
        buffer.writeBoolean(question.chainAbout().isPresent());
        question.chainAbout().ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
    }

    /**
     * A question written by {@link #writeQuestion}. Every number is clamped by {@link CraftFetchQuestion} itself, and
     * at most {@value CraftFetchQuestion#MAX_NAMED} named lines are read whatever the length prefix claims. A chain
     * refusal the record would not keep — one without a bottleneck — is dropped by the record as well, and a name this
     * build does not know reads as none.
     */
    private static Optional<CraftFetchQuestion<ItemKey>> readQuestion(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean())
            return Optional.empty();
        int asked = buffer.readVarInt();
        int ready = buffer.readVarInt();
        int stockRuns = buffer.readVarInt();
        int madeRuns = buffer.readVarInt();
        int roomRuns = buffer.readVarInt();
        Optional<ItemKey> about = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                : Optional.empty();
        long producing = buffer.readVarLong();
        int count = Math.min(Math.max(0, buffer.readVarInt()), CraftFetchQuestion.MAX_NAMED);
        List<CraftFetchQuestion.Line<ItemKey>> named = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            named.add(new CraftFetchQuestion.Line<>(ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong()));
        Optional<PlanRefusal> chainRefusal = planRefusal(buffer.readUtf(MAX_REFUSAL_NAME));
        Optional<ItemKey> chainAbout = buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer))
                : Optional.empty();
        return Optional.of(new CraftFetchQuestion<>(asked, ready, stockRuns, madeRuns, roomRuns, about, producing,
                named, chainRefusal, chainAbout));
    }

    /** The plan refusal by name; a name this build does not know reads as none. */
    private static Optional<PlanRefusal> planRefusal(String name) {
        if (name == null || name.isEmpty())
            return Optional.empty();
        for (PlanRefusal refusal : PlanRefusal.values()) {
            if (refusal.name().equals(name))
                return Optional.of(refusal);
        }
        return Optional.empty();
    }

    /** The outcome this answer reports, for the screen. */
    public TerminalCraftOutcome outcome() {
        return new TerminalCraftOutcome(result, crafted, about, refusal, question);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
