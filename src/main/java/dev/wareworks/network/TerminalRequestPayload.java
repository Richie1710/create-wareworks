package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "please fetch {@code amount} of {@code key} into this terminal" (ADR-019).
 * <p>
 * <b>Nothing in it is trusted.</b> The server resolves the payload against the menu the sending player really has open
 * (the {@code containerId} must match), and the terminal then matches {@code key} against its controller's own stock
 * index and clamps {@code amount} — see {@code WarehouseTerminalBlockEntity#requestFromTerminal}. A payload whose menu
 * does not exist is dropped silently: a player cannot request anything by sending packets alone.
 * <p>
 * <b>The acknowledgement is consent, not permission</b> (M15 part 2, {@code docs/warehouse-system.md} §3.6.6). Whether
 * a click crosses a stock keeper's reserve or maximum is decided on the <b>server</b>, which measures it again for
 * every payload — including a confirmed one, because the warehouse moves between the question and the answer. This
 * field only says what the player has agreed to pay: a cost it does not cover leaves the request unmade and answers
 * with {@link TerminalConfirmPayload} instead. {@link RequestAcknowledgement#ANY} is what a ctrl-click sends, which the
 * design makes the way to skip the question; it unlocks nothing, because a reserve never holds items back from the
 * player at the terminal in the first place ({@code core.stock.StockAccess}).
 *
 * @param containerId  the menu the player has open
 * @param key          the item the screen offered
 * @param amount       how much the player asked for
 * @param acknowledged what the player accepted, {@link RequestAcknowledgement#NONE} for a plain first click
 */
public record TerminalRequestPayload(int containerId, ItemKey key, int amount,
                                     RequestAcknowledgement acknowledged) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalRequestPayload::write, TerminalRequestPayload::new);

    /** The answer is not given: a plain click, which is what makes the terminal ask. */
    private static final int ACKNOWLEDGED_NONE = 0;
    /** "Whatever it costs": the ctrl-click. */
    private static final int ACKNOWLEDGED_ANY = 1;
    /** The three numbers the player accepted follow. */
    private static final int ACKNOWLEDGED_AMOUNTS = 2;

    public TerminalRequestPayload {
        Objects.requireNonNull(key, "key");
        if (acknowledged == null)
            acknowledged = RequestAcknowledgement.NONE;
    }

    /** A plain request, i.e. one the player has agreed to nothing for yet. */
    public TerminalRequestPayload(int containerId, ItemKey key, int amount) {
        this(containerId, key, amount, RequestAcknowledgement.NONE);
    }

    private TerminalRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarInt(),
                readAcknowledgement(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        ItemKey.STREAM_CODEC.encode(buffer, key);
        buffer.writeVarInt(amount);
        writeAcknowledgement(buffer, acknowledged);
    }

    /**
     * The acknowledgement as one tag and, only when it names amounts, the three numbers. A plain click — the payload
     * every request without stock keepers is — therefore costs a single zero byte.
     */
    private static void writeAcknowledgement(RegistryFriendlyByteBuf buffer, RequestAcknowledgement acknowledged) {
        if (acknowledged.any()) {
            buffer.writeVarInt(ACKNOWLEDGED_ANY);
            return;
        }
        if (!acknowledged.given()) {
            buffer.writeVarInt(ACKNOWLEDGED_NONE);
            return;
        }
        buffer.writeVarInt(ACKNOWLEDGED_AMOUNTS);
        buffer.writeVarLong(acknowledged.fromReserve());
        buffer.writeVarLong(acknowledged.pastMaximum());
        buffer.writeVarLong(acknowledged.ingredientReserve());
    }

    /**
     * A tag this build does not know reads as "nothing accepted", i.e. the server asks instead of acting.
     * <p>
     * It is the last field of the payload, so an unknown tag leaves nothing unread and cannot shift anything after it.
     * A field appended here must be read in the {@code ACKNOWLEDGED_AMOUNTS} branch <b>and</b> in the default one, or
     * the fallback stops being safe (see {@code TerminalStockPayload#readEntries} for the same rule).
     */
    private static RequestAcknowledgement readAcknowledgement(RegistryFriendlyByteBuf buffer) {
        return switch (buffer.readVarInt()) {
            case ACKNOWLEDGED_ANY -> RequestAcknowledgement.ANY;
            case ACKNOWLEDGED_AMOUNTS -> new RequestAcknowledgement(false, buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readVarLong());
            default -> RequestAcknowledgement.NONE;
        };
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
