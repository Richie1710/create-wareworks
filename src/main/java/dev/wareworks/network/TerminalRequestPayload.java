package dev.wareworks.network;

import java.util.Objects;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
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
 *
 * @param containerId the menu the player has open
 * @param key         the item the screen offered
 * @param amount      how much the player asked for
 */
public record TerminalRequestPayload(int containerId, ItemKey key, int amount) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalRequestPayload::write, TerminalRequestPayload::new);

    public TerminalRequestPayload {
        Objects.requireNonNull(key, "key");
    }

    private TerminalRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        ItemKey.STREAM_CODEC.encode(buffer, key);
        buffer.writeVarInt(amount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
