package dev.wareworks.network;

import dev.wareworks.Wareworks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client, once at login: whether this world's warehouses are in package mode (issue #40), as the server holds
 * it (review fix, 2026-11-22). A client's screens and goggles read this ({@code PackageModeGate#enabledOnClient}) and not
 * the synced server config: NeoForge sends a server config's file bytes as they are at login, while the server keeps the
 * value it read at world load until it restarts ({@code worldRestart}), so an operator who edits the file of a running
 * server would otherwise have every player who joins afterwards see the other mode.
 *
 * @param enabled the switch as the running server has it
 */
public record PackageModeSwitchPayload(boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PackageModeSwitchPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("package_mode_switch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PackageModeSwitchPayload> STREAM_CODEC =
            CustomPacketPayload.codec(PackageModeSwitchPayload::write, PackageModeSwitchPayload::decode);

    private static PackageModeSwitchPayload decode(RegistryFriendlyByteBuf buffer) {
        return new PackageModeSwitchPayload(buffer.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(enabled);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
