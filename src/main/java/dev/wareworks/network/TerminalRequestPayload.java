package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import net.minecraft.core.BlockPos;
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
 * <p>
 * <b>The door is a position and nothing else</b> (M27, issue #18, ADR-065 D2): the package door the click delivers to,
 * or none for here. No address is ever sent — the payload has no text field at all — and the server checks the
 * position against the terminal's own warehouse after reach, amount and controller and before the stock is looked at
 * ({@code WarehouseTerminalBlockEntity#requestFromTerminal}), so a hostile one from a player who may use the terminal
 * is answered "not a package door of this warehouse" and the world is read at it no further. Only a click on a stock
 * cell carries one; the crafting panel's Fetch, a recipe viewer's lay and a clipboard order have payloads of their own
 * without it, so they always come here.
 *
 * @param containerId  the menu the player has open
 * @param key          the item the screen offered
 * @param amount       how much the player asked for
 * @param door         the package door to deliver to; empty for here
 * @param acknowledged what the player accepted, {@link RequestAcknowledgement#NONE} for a plain first click
 */
public record TerminalRequestPayload(int containerId, ItemKey key, int amount, Optional<BlockPos> door,
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
        door = door == null ? Optional.empty() : door.map(BlockPos::immutable);
        if (acknowledged == null)
            acknowledged = RequestAcknowledgement.NONE;
    }

    /** A request to here with what the player accepted for it: every shape this payload had before M27. */
    public TerminalRequestPayload(int containerId, ItemKey key, int amount, RequestAcknowledgement acknowledged) {
        this(containerId, key, amount, Optional.empty(), acknowledged);
    }

    /** A plain request to here, i.e. one the player has agreed to nothing for yet. */
    public TerminalRequestPayload(int containerId, ItemKey key, int amount) {
        this(containerId, key, amount, RequestAcknowledgement.NONE);
    }

    private TerminalRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarInt(),
                buffer.readOptional(in -> in.readBlockPos()), readAcknowledgement(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        ItemKey.STREAM_CODEC.encode(buffer, key);
        buffer.writeVarInt(amount);
        // Before the acknowledgement, so the acknowledgement stays the last field and its unknown-tag rule still holds
        // (M27): an absent door is one zero byte.
        buffer.writeOptional(door, (out, pos) -> out.writeBlockPos(pos));
        writeAcknowledgement(buffer, acknowledged);
    }

    /**
     * The acknowledgement as one tag and, only when it names amounts, the three numbers. A plain click to here — the
     * payload every request without stock keepers and doors is — therefore ends in two zero bytes, the absent door's and
     * this one.
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
