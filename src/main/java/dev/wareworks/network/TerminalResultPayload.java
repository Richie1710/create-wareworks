package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.PlanRefusal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: what became of a {@link TerminalRequestPayload} (ADR-019).
 * <p>
 * The screen shows one line for a few seconds: the granted amount ("Requested Diamond x10") or the reason the
 * controller refused, the same reasons a warehouse output shows in its goggle tooltip. A click that was <b>merged</b>
 * into an open request of this terminal ({@code docs/warehouse-system.md} §7.2) carries a {@link #pending()} above
 * {@link #amount()}, and the screen then names that total, so a player clicking ten times sees the request grow instead
 * of ten identical lines.
 *
 * @param containerId the menu the answer belongs to
 * @param key         the requested item
 * @param amount      the amount this request added (0 when refused)
 * @param pending     what the terminal's open request for that item waits for now, merged total included (0 when refused)
 * @param producing   items of {@link #amount()} that are not in stock but are being made by a production order this
 *                    request started (M11, ADR-024); 0 when everything came from stock
 * @param rejection   why it was refused; empty when it was accepted
 * @param refusal     why the production <b>chain</b> behind the item could not be planned (M20, issue #4, ADR-032),
 *                    which is the answer a player can act on: the generic rejection says "not enough in stock", this
 *                    says what the chain really ran into. Empty for every request that never planned one
 * @param about       the item {@link #refusal()} is about, regularly <b>not</b> {@link #key()}: a click on a chest is
 *                    refused because three oak logs are missing, and naming them is the whole point. Present exactly
 *                    when {@link #refusal()} is ({@code RequestResult})
 */
public record TerminalResultPayload(int containerId, ItemKey key, int amount, int pending, int producing,
                                    Optional<RequestRejection> rejection, Optional<PlanRefusal> refusal,
                                    Optional<ItemKey> about) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalResultPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalResultPayload::write, TerminalResultPayload::decode);

    /**
     * Longest {@link PlanRefusal} name a payload may claim; the bound is what keeps a hostile one from claiming a
     * megabyte, exactly as {@code StoppedProduct}'s cause name is bounded.
     */
    private static final int MAX_REFUSAL_NAME = 32;

    public TerminalResultPayload {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rejection, "rejection");
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(about, "about");
        amount = Math.max(0, amount);
        pending = Math.max(0, pending);
        producing = Math.max(0, producing);
        // A refusal without its item could name nothing, and an item without a refusal says nothing: a payload that
        // lost one half on the wire reads as the plain answer it was before M20 rather than as half a sentence.
        if (refusal.isEmpty() || about.isEmpty()) {
            refusal = Optional.empty();
            about = Optional.empty();
        }
    }

    /** The answer to a request that never walked a production plan, i.e. every shape this payload had before M20. */
    public TerminalResultPayload(int containerId, ItemKey key, int amount, int pending, int producing,
            Optional<RequestRejection> rejection) {
        this(containerId, key, amount, pending, producing, rejection, Optional.empty(), Optional.empty());
    }

    /** The answer to a request for {@code key} made in the menu {@code containerId}. */
    public static TerminalResultPayload of(int containerId, ItemKey key, RequestResult result) {
        return new TerminalResultPayload(containerId, key, result.granted(), result.pending(), result.producing(),
                result.rejection(), result.refusal(), result.about());
    }

    /**
     * Reads what {@link #write} wrote. A static reader rather than a constructor, because the last field is only on
     * the wire while the one before it says so, and that decision needs a statement.
     */
    private static TerminalResultPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        ItemKey key = ItemKey.STREAM_CODEC.decode(buffer);
        int amount = buffer.readVarInt();
        int pending = buffer.readVarInt();
        int producing = buffer.readVarInt();
        Optional<RequestRejection> rejection = readRejection(buffer);
        // The item is read from what was *written*, not from what this build understands: a refusal constant a future
        // server has and this client has not still leaves the item's bytes in the stream, and skipping them would
        // desynchronise the rest of the connection rather than lose one sentence.
        String encodedRefusal = buffer.readUtf(MAX_REFUSAL_NAME);
        Optional<PlanRefusal> refusal = refusalOf(encodedRefusal);
        Optional<ItemKey> about = encodedRefusal.isEmpty() ? Optional.empty()
                : Optional.of(ItemKey.STREAM_CODEC.decode(buffer));
        return new TerminalResultPayload(containerId, key, amount, pending, producing, rejection, refusal, about);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        ItemKey.STREAM_CODEC.encode(buffer, key);
        buffer.writeVarInt(amount);
        buffer.writeVarInt(pending);
        buffer.writeVarInt(producing);
        buffer.writeVarInt(rejection.map(reason -> reason.ordinal() + 1).orElse(0));
        // Appended after everything M15 sent. The refusal travels by *name*, exactly as a StoppedProduct's cause does, so
        // the declaration order of PlanRefusal stays free — it is a derived, unsaved enum whose order says what the planner
        // tests, and reordering it must not shift a sentence on another build. The item is written only behind a refusal,
        // so an answer with no plan behind it — every answer a warehouse without chains ever gives — costs one zero byte.
        buffer.writeUtf(refusal.map(PlanRefusal::name).orElse(""), MAX_REFUSAL_NAME);
        about.ifPresent(item -> ItemKey.STREAM_CODEC.encode(buffer, item));
    }

    private static Optional<RequestRejection> readRejection(RegistryFriendlyByteBuf buffer) {
        int encoded = buffer.readVarInt() - 1;
        RequestRejection[] values = RequestRejection.values();
        return encoded < 0 || encoded >= values.length ? Optional.empty() : Optional.of(values[encoded]);
    }

    /**
     * The plan refusal by <b>name</b>. A name this client does not know reads as "no plan refusal", which leaves the plain
     * rejection as the answer instead of a raw lang key — and, because the name was still read from the stream, the item
     * behind it is still consumed.
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

    /** Whether the request was accepted. */
    public boolean isAccepted() {
        return rejection.isEmpty();
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
