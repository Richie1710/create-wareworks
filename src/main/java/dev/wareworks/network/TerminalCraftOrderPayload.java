package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.terminal.RequestAcknowledgement;
import dev.wareworks.core.terminal.RequestConfirmation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: what the player did to the craft order of the terminal they have open — the crafting panel's
 * Fetch, and the Answer, Resume and Cancel of the order it starts (issue #24, slice 2,
 * {@code docs/warehouse-system.md} §3.4.5, ADR-062).
 * <p>
 * <b>Nothing in it is trusted.</b> The server resolves it against the menu the player really has open (the
 * {@code containerId} must match), the terminal re-validates reach and aisle, and the recipe is looked up on the server
 * for the grid's exact keys. What the Fetch brings is measured on the server against the warehouse and the buffer as
 * they are, so {@link #crafts()} is a wish and {@link #acceptedProducing()} a statement of <b>consent</b>, never an
 * instruction: a fetch that cannot do what they cover is asked about again instead of being started.
 * <p>
 * <b>Exactly {@value CraftGrid#SIZE} cells</b>, written for every action so the shape never depends on the action; only
 * a Fetch reads them. A count other than {@value CraftGrid#SIZE}, like an action name this build does not know, reads
 * as {@link Action#NONE}, which the server drops.
 *
 * @param containerId       the menu the player has open
 * @param action            what they pressed
 * @param cells             for {@link Action#FETCH}: the grid as laid, row-major
 * @param hint              for {@link Action#FETCH}: the recipe the screen last showed for this grid, if any
 * @param crafts            for {@link Action#FETCH}: how many crafts to fetch for, the amount field's result items
 *                          rounded up to whole crafts by the client (issue #33)
 * @param acceptedProducing for {@link Action#FETCH}: the production total the player accepted when they were asked;
 *                          0 for a first press
 * @param answering         for {@link Action#FETCH}: whether this Fetch is the Yes to the question the server asked,
 *                          which is what lets the server say the warehouse changed in between rather than ask afresh
 * @param acknowledged      for {@link Action#ANSWER}: what the player accepted for the one portion the order stopped
 *                          on, the ordinary terminal confirmation ({@code §3.6.6}). An {@code ANSWER} that accepts
 *                          <b>nothing</b> is the order button's "show me that question again" and changes no state
 * @param question          for {@link Action#ANSWER}: the question that answer was given to, as the screen showed it
 *                          (issue #24 review fix). The server takes the answer only for the question it is asking now,
 *                          and puts that one up instead when another player's answer has moved the order on; empty for
 *                          the "show me that question again" and every other action
 */
public record TerminalCraftOrderPayload(int containerId, Action action, CraftGrid<ItemKey> cells,
                                        Optional<ResourceLocation> hint, int crafts, long acceptedProducing,
                                        boolean answering, RequestAcknowledgement acknowledged,
                                        Optional<RequestConfirmation<ItemKey>> question)
        implements CustomPacketPayload {
    /** What a player can do to a terminal's craft order. Travels by <b>name</b>, so the order is free. */
    public enum Action {
        /**
         * Nothing at all: what an action name this build does not know, or a grid of the wrong size, reads as, so such
         * a payload is dropped rather than mistaken for one of the five below. Never sent.
         */
        NONE,
        /** Start a craft order for the grid, or say Yes to the question a Fetch raised. */
        FETCH,
        /** Answer the question one portion of the order raised, or ask for it to be put up again. */
        ANSWER,
        /** Say <b>no</b> to the question one portion raised: nothing is accepted and the order parks. */
        DECLINE,
        /** Give a parked order another try. */
        RESUME,
        /** Give the order up; only its own share of its requests is given back. */
        CANCEL;

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

    /** Longest action name a payload may claim; the bound keeps a hostile one from claiming a megabyte. */
    private static final int MAX_ACTION_NAME = 16;

    public static final CustomPacketPayload.Type<TerminalCraftOrderPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftOrderPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftOrderPayload::write, TerminalCraftOrderPayload::decode);

    public TerminalCraftOrderPayload {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(hint, "hint");
        if (acknowledged == null)
            acknowledged = RequestAcknowledgement.NONE;
        if (question == null)
            question = Optional.empty();
        crafts = Math.max(0, crafts);
        acceptedProducing = Math.max(0L, acceptedProducing);
    }

    /** An action that carries nothing but itself: Decline, Resume and Cancel. */
    public static TerminalCraftOrderPayload of(int containerId, Action action) {
        return new TerminalCraftOrderPayload(containerId, action, CraftGrid.empty(), Optional.empty(), 0, 0L, false,
                RequestAcknowledgement.NONE, Optional.empty());
    }

    /**
     * A Fetch of {@code crafts} crafts of {@code cells}: a first press with {@code answering} false and nothing
     * accepted, or the Yes to the question with exactly the crafts and the production total that question named.
     */
    public static TerminalCraftOrderPayload fetch(int containerId, CraftGrid<ItemKey> cells,
            Optional<ResourceLocation> hint, int crafts, long acceptedProducing, boolean answering) {
        return new TerminalCraftOrderPayload(containerId, Action.FETCH, cells, hint, crafts, acceptedProducing,
                answering, RequestAcknowledgement.NONE, Optional.empty());
    }

    /** The answer to {@code question}, the question one portion of the order raised, as the screen showed it. */
    public static TerminalCraftOrderPayload answer(int containerId, RequestConfirmation<ItemKey> question,
            RequestAcknowledgement acknowledged) {
        return new TerminalCraftOrderPayload(containerId, Action.ANSWER, CraftGrid.empty(), Optional.empty(), 0, 0L,
                false, acknowledged, Optional.of(Objects.requireNonNull(question, "question")));
    }

    /** The order button's Answer: no question named and nothing accepted, i.e. "put the question up again". */
    public static TerminalCraftOrderPayload showQuestion(int containerId) {
        return new TerminalCraftOrderPayload(containerId, Action.ANSWER, CraftGrid.empty(), Optional.empty(), 0, 0L,
                false, RequestAcknowledgement.NONE, Optional.empty());
    }

    /**
     * Reads what {@link #write} wrote. A static reader rather than a constructor, because the cell count decides what
     * the action really is, and that decision needs a statement.
     */
    private static TerminalCraftOrderPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        Action action = Action.byName(buffer.readUtf(MAX_ACTION_NAME)).orElse(Action.NONE);
        Optional<CraftGrid<ItemKey>> cells = TerminalCraftPayload.readCells(buffer);
        Optional<ResourceLocation> hint = TerminalCraftPayload.readRecipeId(buffer);
        int crafts = buffer.readVarInt();
        long acceptedProducing = buffer.readVarLong();
        boolean answering = buffer.readBoolean();
        Optional<RequestConfirmation<ItemKey>> question = TerminalListActionPayload.readAnswered(buffer);
        RequestAcknowledgement acknowledged = TerminalListActionPayload.readAcknowledgement(buffer);
        return new TerminalCraftOrderPayload(containerId, cells.isPresent() ? action : Action.NONE,
                cells.orElse(CraftGrid.empty()), hint, crafts, acceptedProducing, answering, acknowledged, question);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        // By name, not by ordinal: a build that does not know an action reads it as NONE instead of as a shifted one.
        buffer.writeUtf(action.name(), MAX_ACTION_NAME);
        TerminalCraftPayload.writeCells(buffer, cells);
        TerminalCraftPayload.writeRecipeId(buffer, hint);
        buffer.writeVarInt(crafts);
        buffer.writeVarLong(acceptedProducing);
        buffer.writeBoolean(answering);
        // The question an answer was given to, as the clipboard order's answer names its own: one byte without one.
        TerminalListActionPayload.writeAnswered(buffer, question);
        // Last, as one tag and only the numbers it names: an action that accepts nothing costs a single zero byte.
        TerminalListActionPayload.writeAcknowledgement(buffer, acknowledged);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
