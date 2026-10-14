package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the crafting panel of the terminal the player has open (M32, issue #24,
 * {@code docs/warehouse-system.md} §3.4.5) — "this is the grid I laid; tell me what it makes", or "craft it".
 * <p>
 * <b>Nothing in it is trusted.</b> The server resolves it against the menu the player really has open (the
 * {@code containerId} must match), the terminal re-validates reach, and the recipe is looked up on the server for the
 * grid's exact keys; the hint only says which of two matching recipes the screen showed. A craft takes its ingredients
 * from the terminal's own buffer, never from anything the payload describes, so a player cannot craft anything by
 * sending packets alone that they could not craft by clicking.
 * <p>
 * <b>Exactly {@value CraftGrid#SIZE} cells.</b> The cell count travels on the wire so that a payload of another shape
 * is recognised rather than misread: a count other than {@value CraftGrid#SIZE}, like an action name this build does
 * not know, reads as {@link Action#NONE}, which the server drops. At most {@value CraftGrid#SIZE} cells are decoded,
 * whatever the count claims.
 *
 * @param containerId the menu the player has open
 * @param action      what the player asked for
 * @param cells       the grid as laid, row-major
 * @param hint        the recipe the screen last showed for this grid, as the server named it; empty when it showed
 *                    none
 */
public record TerminalCraftPayload(int containerId, Action action, CraftGrid<ItemKey> cells,
                                   Optional<ResourceLocation> hint) implements CustomPacketPayload {
    /** What a player can ask of the crafting panel. Travels by <b>name</b>, so the order is free. */
    public enum Action {
        /**
         * Nothing at all: what an action name this build does not know, or a grid of the wrong size, reads as, so such
         * a payload is dropped rather than mistaken for one of the three below. Never sent.
         */
        NONE,
        /** The grid changed: resolve its recipe and result for the panel. Moves nothing and answers nothing. */
        PREVIEW,
        /** A click on the result cell: craft once. */
        CRAFT,
        /** A Shift-click on the result cell: craft as often as the delivered ingredients allow. */
        CRAFT_ALL;

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
    /** Longest recipe id a payload may claim, for the same reason. */
    static final int MAX_RECIPE_ID = 512;

    public static final CustomPacketPayload.Type<TerminalCraftPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_craft"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCraftPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalCraftPayload::write, TerminalCraftPayload::decode);

    public TerminalCraftPayload {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(hint, "hint");
    }

    /**
     * Reads what {@link #write} wrote. A static reader rather than a constructor, because the cell count decides what
     * the action really is, and that decision needs a statement.
     */
    private static TerminalCraftPayload decode(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        Action action = Action.byName(buffer.readUtf(MAX_ACTION_NAME)).orElse(Action.NONE);
        Optional<CraftGrid<ItemKey>> cells = readCells(buffer);
        Optional<ResourceLocation> hint = readRecipeId(buffer);
        if (cells.isEmpty())
            return new TerminalCraftPayload(containerId, Action.NONE, CraftGrid.empty(), hint);
        return new TerminalCraftPayload(containerId, action, cells.get(), hint);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        // By name, not by ordinal: a build that does not know an action reads it as NONE instead of as a shifted one.
        buffer.writeUtf(action.name(), MAX_ACTION_NAME);
        writeCells(buffer, cells);
        writeRecipeId(buffer, hint);
    }

    /** A grid as its cell count and, per cell, whether it names an item and which one. */
    static void writeCells(RegistryFriendlyByteBuf buffer, CraftGrid<ItemKey> cells) {
        buffer.writeVarInt(cells.cells().size());
        for (Optional<ItemKey> cell : cells.cells()) {
            buffer.writeBoolean(cell.isPresent());
            cell.ifPresent(key -> ItemKey.STREAM_CODEC.encode(buffer, key));
        }
    }

    /**
     * A grid written by {@link #writeCells}. At most {@value CraftGrid#SIZE} cells are read whatever the count claims,
     * and a count other than {@value CraftGrid#SIZE} reads as no grid at all, so a payload of another shape is
     * recognised rather than misread.
     */
    static Optional<CraftGrid<ItemKey>> readCells(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        int read = Math.min(Math.max(0, count), CraftGrid.SIZE);
        List<Optional<ItemKey>> cells = new ArrayList<>(CraftGrid.SIZE);
        for (int i = 0; i < read; i++)
            cells.add(buffer.readBoolean() ? Optional.of(ItemKey.STREAM_CODEC.decode(buffer)) : Optional.empty());
        return count == CraftGrid.SIZE ? Optional.of(new CraftGrid<>(cells)) : Optional.empty();
    }

    /**
     * A recipe id as a bounded string, the empty string for none. An id longer than the bound is sent as none rather
     * than failing the encoder: it is a hint either way, and the server resolves the grid without one.
     */
    static void writeRecipeId(RegistryFriendlyByteBuf buffer, Optional<ResourceLocation> recipe) {
        String id = recipe.map(ResourceLocation::toString).filter(text -> text.length() <= MAX_RECIPE_ID).orElse("");
        buffer.writeUtf(id, MAX_RECIPE_ID);
    }

    /**
     * A recipe id written by {@link #writeRecipeId}. A string that is no valid id reads as none instead of throwing
     * inside a packet handler: the id is a hint either way.
     */
    static Optional<ResourceLocation> readRecipeId(RegistryFriendlyByteBuf buffer) {
        String id = buffer.readUtf(MAX_RECIPE_ID);
        return id.isEmpty() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(id));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
