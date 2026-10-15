package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.ListOrderConfirmation;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: what the player did to the clipboard order of the terminal they have open (M23, issue #19,
 * {@code docs/warehouse-system.md} §3.4.4).
 * <p>
 * <b>Nothing in it is trusted.</b> The server resolves it against the menu the player really has open, the terminal
 * re-validates reach and aisle, and the clipboard it acts on is the one in its own list slot — never one the payload
 * describes. The whole list is then measured again on the server, so the three numbers here are a statement of
 * <b>consent</b> and not an instruction: a list that costs more than they cover is asked about a second time instead
 * of being started ({@link ListOrderConfirmation#covers}).
 *
 * @param containerId       the menu the player has open
 * @param action            what they pressed
 * @param acceptedMissing   for {@link Action#FETCH}: items they accept not getting, i.e. what they were shown as
 *                          missing; 0 for a first press, which is what makes the terminal ask
 * @param acceptedProducing for {@link Action#FETCH}: items they accept having made for them
 * @param acceptedDropped   for {@link Action#FETCH}: entries they accept the entry cap leaving on the clipboard, i.e.
 *                          what they were shown as not taken (M23 review fix)
 * @param acknowledged      for {@link Action#ANSWER}: what they accepted for the one portion the order stopped on,
 *                          which is the ordinary terminal confirmation ({@code §3.6.6}). An {@code ANSWER} that accepts
 *                          <b>nothing</b> is the button's "show me that question again" and changes no state
 * @param question          for {@link Action#ANSWER}: the question that answer was given to, as the screen showed it
 *                          (issue #24 review fix). The server takes the answer only for the question it is asking now,
 *                          and puts that one up instead when another player's answer has moved the order on; empty for
 *                          the "show me that question again" and every other action
 */
public record TerminalListActionPayload(int containerId, Action action, long acceptedMissing, long acceptedProducing,
                                        long acceptedDropped, RequestAcknowledgement acknowledged,
                                        Optional<RequestConfirmation<ItemKey>> question)
        implements CustomPacketPayload {
    /** What a player can do to a clipboard order from the screen. Travels by <b>name</b>, so the order is free. */
    public enum Action {
        /**
         * Nothing at all: what an action name this build does not know reads as, so a payload from another version is
         * dropped rather than mistaken for one of the five below. Never sent.
         */
        NONE,
        /** Start the list, or start it again after it was answered. */
        FETCH,
        /** Give the order up. */
        CANCEL,
        /** Give a parked order another try. */
        RESUME,
        /** Answer the question one portion raised, or ask for it to be put up again. */
        ANSWER,
        /** Say <b>no</b> to the question one portion raised: nothing is accepted and the order parks. */
        DECLINE;

        /** The action with that name, or empty for an unknown one — which the server then simply drops. */
        public static Optional<Action> byName(String name) {
            if (name == null)
                return Optional.empty();
            for (Action action : values()) {
                if (action.name().equals(name))
                    return Optional.of(action);
            }
            return Optional.empty();
        }
    }

    public static final CustomPacketPayload.Type<TerminalListActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_list_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalListActionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalListActionPayload::write, TerminalListActionPayload::new);

    public TerminalListActionPayload {
        Objects.requireNonNull(action, "action");
        if (acknowledged == null)
            acknowledged = RequestAcknowledgement.NONE;
        if (question == null)
            question = Optional.empty();
        acceptedMissing = Math.max(0L, acceptedMissing);
        acceptedProducing = Math.max(0L, acceptedProducing);
        acceptedDropped = Math.max(0L, acceptedDropped);
    }

    /** An action that carries no numbers: Cancel, Resume and Decline. */
    public TerminalListActionPayload(int containerId, Action action) {
        this(containerId, action, 0L, 0L, 0L, RequestAcknowledgement.NONE, Optional.empty());
    }

    /** A Fetch with what the player accepted after reading the question. */
    public static TerminalListActionPayload fetch(int containerId, long acceptedMissing, long acceptedProducing,
            long acceptedDropped) {
        return new TerminalListActionPayload(containerId, Action.FETCH, acceptedMissing, acceptedProducing,
                acceptedDropped, RequestAcknowledgement.NONE, Optional.empty());
    }

    /** The answer to {@code question}, the question one portion raised, as the screen showed it. */
    public static TerminalListActionPayload answer(int containerId, RequestConfirmation<ItemKey> question,
            RequestAcknowledgement acknowledged) {
        return new TerminalListActionPayload(containerId, Action.ANSWER, 0L, 0L, 0L, acknowledged,
                Optional.of(Objects.requireNonNull(question, "question")));
    }

    /** The list button's Answer: no question named and nothing accepted, i.e. "put the question up again". */
    public static TerminalListActionPayload showQuestion(int containerId) {
        return new TerminalListActionPayload(containerId, Action.ANSWER, 0L, 0L, 0L, RequestAcknowledgement.NONE,
                Optional.empty());
    }

    private TerminalListActionPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), Action.byName(buffer.readUtf()).orElse(Action.NONE), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(), readAnswered(buffer), buffer);
    }

    /** The second half of the reader: the question comes before the acknowledgement, which stays the last field. */
    private TerminalListActionPayload(int containerId, Action action, long acceptedMissing, long acceptedProducing,
            long acceptedDropped, Optional<RequestConfirmation<ItemKey>> question, RegistryFriendlyByteBuf buffer) {
        this(containerId, action, acceptedMissing, acceptedProducing, acceptedDropped, readAcknowledgement(buffer),
                question);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUtf(action.name());
        buffer.writeVarLong(acceptedMissing);
        buffer.writeVarLong(acceptedProducing);
        buffer.writeVarLong(acceptedDropped);
        writeAnswered(buffer, question);
        writeAcknowledgement(buffer, acknowledged);
    }

    /**
     * The question an answer was given to, as a flag and, when present, the question in the shape
     * {@link TerminalConfirmPayload} sends it (issue #24 review fix). Every action that names none costs one byte.
     * Shared with {@link TerminalCraftOrderPayload}, whose answer names its question the same way.
     */
    static void writeAnswered(RegistryFriendlyByteBuf buffer, Optional<RequestConfirmation<ItemKey>> question) {
        buffer.writeBoolean(question.isPresent());
        question.ifPresent(asked -> TerminalConfirmPayload.writeQuestion(buffer, asked));
    }

    /** Reads what {@link #writeAnswered} wrote. */
    static Optional<RequestConfirmation<ItemKey>> readAnswered(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? Optional.of(TerminalConfirmPayload.readQuestion(buffer)) : Optional.empty();
    }

    /**
     * The acknowledgement as one tag and, only when it names amounts, the four numbers. An action that accepts nothing
     * — every Cancel, Resume and first Fetch — therefore costs a single zero byte.
     * <p>
     * The fourth number is what a list order adds to the three a click sends ({@link RequestAcknowledgement#produced}):
     * a portion that would have items <b>made</b> is asked about, because nobody is at the terminal.
     */
    static void writeAcknowledgement(RegistryFriendlyByteBuf buffer, RequestAcknowledgement acknowledged) {
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
        buffer.writeVarLong(acknowledged.produced());
    }

    /**
     * A tag this build does not know reads as "nothing accepted", i.e. the server asks instead of acting. It is the
     * last field of the payload, so an unknown tag leaves nothing unread — and of {@link TerminalCraftOrderPayload}'s
     * too, which shares this codec for the answer to a craft order's portion (issue #24).
     */
    static RequestAcknowledgement readAcknowledgement(RegistryFriendlyByteBuf buffer) {
        return switch (buffer.readVarInt()) {
            case ACKNOWLEDGED_ANY -> RequestAcknowledgement.ANY;
            case ACKNOWLEDGED_AMOUNTS -> new RequestAcknowledgement(false, buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong());
            default -> RequestAcknowledgement.NONE;
        };
    }

    /** The answer is not given, which is what makes the terminal ask. */
    private static final int ACKNOWLEDGED_NONE = 0;
    /** "Whatever it costs". */
    private static final int ACKNOWLEDGED_ANY = 1;
    /** The four numbers the player accepted follow. */
    private static final int ACKNOWLEDGED_AMOUNTS = 2;

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
