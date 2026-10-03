package dev.wareworks.content.controller;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.warehouse.AisleName;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Whether a warehouse member belongs to an aisle, as shown in its goggle tooltip: its {@link StorageAddress},
 * "misaligned" (it stands at a rack position but violates the facing rule), an address the crane cannot reach, or
 * "not part of an aisle".
 * <p>
 * Resolved on the server through {@link WarehouseRegistry#assignmentOf} and synced in the member's client packet. The
 * synced form is a state name and an address of at most a dozen characters, so its size is bounded. Reading never
 * throws: invalid data reads as {@link #NONE}.
 *
 * @param state   assignment state
 * @param address the address, present exactly for the states that name a rack position of their own
 *                ({@link State#hasAddress()}: {@link State#ASSIGNED} and {@link State#UNREACHABLE})
 * @param aisleName the name a player gave that aisle (M25, issue #15, ADR-038), empty where it has none and always
 *                  empty for a state that carries no address. It is a <b>label beside</b> the address and never
 *                  instead of it: this is the goggle line of the block a player is standing at, and the address is
 *                  what the terminal, the crane's lines and every report speak
 */
public record AisleAssignment(State state, Optional<StorageAddress> address, Optional<String> aisleName) {
    public enum State {
        /** Not at a rack position of any registered aisle. */
        NONE,
        /** At a rack position with the correct facing: has an address. */
        ASSIGNED,
        /** At a rack position, but facing the wrong way. */
        MISALIGNED,
        /**
         * At a rack position with the correct facing, so it <b>has</b> an address and keeps its stock — but the rails
         * no longer join its aisle to the crane, so nothing is planned towards it (M22, issue #2).
         * <p>
         * A warehouse's rails are connected by construction, so this is what something cut loose: an aisle whose
         * junction fell outside {@code aisle.maxAisleLength}, or one hanging off the part of the aisle at the dock that
         * a partial scan has had to shorten ({@link dev.wareworks.core.address.NetworkGeometry#withFirstBranchLength}).
         * The member says so on its own goggles rather than looking like a chest nobody wants to use, and it keeps
         * every address and every count it had, so the aisle comes back whole when the rails do.
         */
        UNREACHABLE;

        /** Whether this state names a rack position of its own, i.e. carries an address. */
        public boolean hasAddress() {
            return this == ASSIGNED || this == UNREACHABLE;
        }
    }

    public static final AisleAssignment NONE =
            new AisleAssignment(State.NONE, Optional.empty(), Optional.empty());
    public static final AisleAssignment MISALIGNED =
            new AisleAssignment(State.MISALIGNED, Optional.empty(), Optional.empty());

    private static final String STATE_TAG = "State";
    private static final String ADDRESS_TAG = "Address";
    private static final String AISLE_NAME_TAG = "AisleName";

    public AisleAssignment {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(address, "address");
        if (state.hasAddress() && address.isEmpty())
            throw new IllegalArgumentException("a member at a rack position of its own needs an address");
        if (!state.hasAddress())
            address = Optional.empty();
        // Re-sanitised on construction, so the goggle line can never draw a name the spelling rule would not allow —
        // and dropped with the address, because a block that belongs to no aisle has no aisle name to show (M25).
        aisleName = aisleName == null || !state.hasAddress() ? Optional.<String>empty()
                : aisleName.map(AisleName::sanitize).filter(name -> !name.isEmpty());
    }

    public static AisleAssignment assigned(StorageAddress address) {
        return new AisleAssignment(State.ASSIGNED, Optional.of(Objects.requireNonNull(address, "address")),
                Optional.empty());
    }

    /** The same member, on an aisle the rails no longer join to the crane ({@link State#UNREACHABLE}). */
    public static AisleAssignment unreachable(StorageAddress address) {
        return new AisleAssignment(State.UNREACHABLE, Optional.of(Objects.requireNonNull(address, "address")),
                Optional.empty());
    }

    /**
     * The same assignment with the name its aisle carries (M25, issue #15), or unchanged where there is none.
     * <p>
     * A separate step rather than a second pair of factories: the name is the one part of an assignment that is not
     * geometry, it arrives from a different place ({@code WarehouseControllerBlockEntity#aisleName}), and every
     * existing caller and every test that builds an expected assignment of an unnamed warehouse keeps reading exactly
     * as it did.
     */
    public AisleAssignment withAisleName(Optional<String> name) {
        return new AisleAssignment(state, address, name);
    }

    /**
     * Adds the goggle lines of this assignment at {@code indents}: "Address: A-03-07R", "Misaligned" followed by the
     * member's hint, or "Not part of an aisle". Client only (goggle lines measure the client font).
     *
     * @param misalignedHintKey relative lang key of the hint shown below "Misaligned"
     */
    public void addGoggleLines(List<Component> tooltip, String misalignedHintKey, int indents) {
        switch (state) {
            case ASSIGNED -> WareworksLang.address(address.map(StorageAddress::format).orElse(""), aisleName)
                    .forGoggles(tooltip, indents);
            // The address first and then why nothing happens at it: the member really is this location of this aisle,
            // and a player who reads only "unreachable" would go looking for a misplaced block (M22, issue #2).
            case UNREACHABLE -> {
                WareworksLang.address(address.map(StorageAddress::format).orElse(""), aisleName)
                        .forGoggles(tooltip, indents);
                WareworksLang.translate(WareworksLang.GOGGLES_UNREACHABLE_AISLE).style(ChatFormatting.GOLD)
                        .forGoggles(tooltip, indents);
            }
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
        // Left out while the aisle has no name, which is every aisle of every world built before M25: this tag is part
        // of a member's client packet, and a missing key reads back as "no name".
        aisleName.ifPresent(name -> tag.putString(AISLE_NAME_TAG, name));
    }

    /** Reads an assignment written by {@link #write}. Never throws; unknown or invalid data reads as {@link #NONE}. */
    public static AisleAssignment read(CompoundTag tag) {
        String stateName = tag.getString(STATE_TAG);
        if (State.MISALIGNED.name().equals(stateName))
            return MISALIGNED;
        Optional<String> name = Optional.of(tag.getString(AISLE_NAME_TAG));
        if (State.ASSIGNED.name().equals(stateName))
            return StorageAddress.tryParse(tag.getString(ADDRESS_TAG))
                    .map(address -> assigned(address).withAisleName(name)).orElse(NONE);
        // An unreachable member really is a location of its aisle, so it keeps its address here too: reading it as
        // NONE would make the goggles of a perfectly placed interface say "not part of an aisle" (M22, issue #2).
        if (State.UNREACHABLE.name().equals(stateName))
            return StorageAddress.tryParse(tag.getString(ADDRESS_TAG))
                    .map(address -> unreachable(address).withAisleName(name)).orElse(NONE);
        return NONE;
    }
}
