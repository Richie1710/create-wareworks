package dev.wareworks.content.controller;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Whether a warehouse member belongs to an aisle, as shown in its goggle tooltip: its {@link StorageAddress}, "misaligned"
 * (it stands at a rack position but violates the facing rule) or "not part of an aisle".
 * <p>
 * Resolved on the server through {@link WarehouseRegistry#assignmentOf} and synced in the member's client packet. The
 * synced form is a state name and an address of at most a dozen characters, so its size is bounded. Reading never
 * throws: invalid data reads as {@link #NONE}.
 *
 * @param state   assignment state
 * @param address the address, present exactly for {@link State#ASSIGNED}
 */
public record AisleAssignment(State state, Optional<StorageAddress> address) {
    public enum State {
        /** Not at a rack position of any registered aisle. */
        NONE,
        /** At a rack position with the correct facing: has an address. */
        ASSIGNED,
        /** At a rack position, but facing the wrong way. */
        MISALIGNED
    }

    public static final AisleAssignment NONE = new AisleAssignment(State.NONE, Optional.empty());
    public static final AisleAssignment MISALIGNED = new AisleAssignment(State.MISALIGNED, Optional.empty());

    private static final String STATE_TAG = "State";
    private static final String ADDRESS_TAG = "Address";

    public AisleAssignment {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(address, "address");
        if (state == State.ASSIGNED && address.isEmpty())
            throw new IllegalArgumentException("an assigned member needs an address");
        if (state != State.ASSIGNED)
            address = Optional.empty();
    }

    public static AisleAssignment assigned(StorageAddress address) {
        return new AisleAssignment(State.ASSIGNED, Optional.of(Objects.requireNonNull(address, "address")));
    }

    /**
     * Adds the goggle lines of this assignment at {@code indents}: "Address: A-03-07R", "Misaligned" followed by the
     * member's hint, or "Not part of an aisle". Client only (goggle lines measure the client font).
     *
     * @param misalignedHintKey relative lang key of the hint shown below "Misaligned"
     */
    public void addGoggleLines(List<Component> tooltip, String misalignedHintKey, int indents) {
        switch (state) {
            case ASSIGNED -> WareworksLang.address(address.map(StorageAddress::format).orElse(""))
                    .forGoggles(tooltip, indents);
            case MISALIGNED -> {
                WareworksLang.translate(WareworksLang.GOGGLES_MISALIGNED).style(ChatFormatting.GOLD)
                        .forGoggles(tooltip, indents);
                WareworksLang.translate(misalignedHintKey).style(ChatFormatting.DARK_GRAY).forGoggles(tooltip, indents);
            }
            case NONE -> WareworksLang.translate(WareworksLang.GOGGLES_NO_AISLE).style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip, indents);
        }
    }

    /** Writes this assignment into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putString(STATE_TAG, state.name());
        address.ifPresent(value -> tag.putString(ADDRESS_TAG, value.format()));
    }

    /** Reads an assignment written by {@link #write}. Never throws; unknown or invalid data reads as {@link #NONE}. */
    public static AisleAssignment read(CompoundTag tag) {
        String stateName = tag.getString(STATE_TAG);
        if (State.MISALIGNED.name().equals(stateName))
            return MISALIGNED;
        if (State.ASSIGNED.name().equals(stateName))
            return StorageAddress.tryParse(tag.getString(ADDRESS_TAG)).map(AisleAssignment::assigned).orElse(NONE);
        return NONE;
    }
}
