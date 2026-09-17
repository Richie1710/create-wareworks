package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
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
 */
public record TerminalResultPayload(int containerId, ItemKey key, int amount, int pending, int producing,
                                    Optional<RequestRejection> rejection) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalResultPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalResultPayload::write, TerminalResultPayload::new);

    public TerminalResultPayload {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rejection, "rejection");
        amount = Math.max(0, amount);
        pending = Math.max(0, pending);
        producing = Math.max(0, producing);
    }

    /** The answer to a request for {@code key} made in the menu {@code containerId}. */
    public static TerminalResultPayload of(int containerId, ItemKey key, RequestResult result) {
        return new TerminalResultPayload(containerId, key, result.granted(), result.pending(), result.producing(),
                result.rejection());
    }

    private TerminalResultPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), readRejection(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        ItemKey.STREAM_CODEC.encode(buffer, key);
        buffer.writeVarInt(amount);
        buffer.writeVarInt(pending);
        buffer.writeVarInt(producing);
        buffer.writeVarInt(rejection.map(reason -> reason.ordinal() + 1).orElse(0));
    }

    private static Optional<RequestRejection> readRejection(RegistryFriendlyByteBuf buffer) {
        int encoded = buffer.readVarInt() - 1;
        RequestRejection[] values = RequestRejection.values();
        return encoded < 0 || encoded >= values.length ? Optional.empty() : Optional.of(values[encoded]);
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
