package dev.wareworks.network;

import dev.wareworks.Wareworks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: an action on the Recipe Selector row of a production station's screen (ADR-085, design §13).
 * <p>
 * <b>It names nothing but the menu it came from and the action</b>, like {@link ProductionResumePayload}: which station
 * is meant is decided by the menu the player really has open ({@code ProductionMenu#submitSelector}), and the station
 * decides whether it may (it refuses to switch off while an order of it is engaged, because that order's ingredients
 * are in the line). It moves no item. An action this build does not know does nothing.
 *
 * @param containerId the menu the player has open
 * @param action      what to do
 */
public record ProductionSelectorPayload(int containerId, Action action) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProductionSelectorPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("production_selector"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionSelectorPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ProductionSelectorPayload::write, ProductionSelectorPayload::new);

    /** What the player asked for. Sent by ordinal; a constant is only ever appended. */
    public enum Action {
        /** Switch the station's Recipe Selector (or its recipe slips) off. */
        SWITCH_OFF;

        static Action byOrdinal(int ordinal) {
            Action[] actions = values();
            return ordinal < 0 || ordinal >= actions.length ? null : actions[ordinal];
        }
    }

    private ProductionSelectorPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), Action.byOrdinal(buffer.readVarInt()));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(action == null ? -1 : action.ordinal());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
