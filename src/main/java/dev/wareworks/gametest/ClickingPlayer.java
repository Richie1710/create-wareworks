package dev.wareworks.gametest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * A real {@link ServerPlayer} for the GameTests that click a block <b>through the server's real dispatch</b>,
 * {@code ServerPlayerGameMode#useItemOn} — the method a {@code ServerboundUseItemOnPacket} reaches on a dedicated
 * server, with the interaction event and every listener on it, {@code onItemUseFirst}, the sneak guard, the block's
 * own {@code useItemOn} and {@code useWithoutItem}, and {@code stack.useOn}, in vanilla's order
 * ({@code StationClickGameTests}, {@code StorageFilterClickGameTests}).
 * <p>
 * Its connection keeps what the server sends it instead of writing it to a socket — the screen it opens, the chat lines
 * it gets — the way NeoForge's own {@code FakePlayer} keeps a connection that drops them. It is no {@code FakePlayer}:
 * the stations deliberately treat a fake player as a Deployer, and a storage filter slot refuses one.
 * <p>
 * It is never added to the level or the player list, so it never ticks and no other test sees it. Its id is fixed, so
 * the player list keeps a single stats and advancement record for it however often these tests run — the way
 * {@code FakePlayerFactory} reuses its own profile. A new player starts in survival, so a placed block costs one.
 */
final class ClickingPlayer extends ServerPlayer {
    private static final GameProfile PROFILE = new GameProfile(
            UUID.nameUUIDFromBytes("wareworks-click".getBytes(StandardCharsets.UTF_8)), "wareworks-click");

    /** The chat and action bar lines sent to this player, in order. */
    final List<ClientboundSystemChatPacket> told = new ArrayList<>();

    private ClickingPlayer(ServerLevel level) {
        super(level.getServer(), level, PROFILE, ClientInformation.createDefault());
        this.connection = new Keeping(level.getServer(), this);
        setGameMode(GameType.SURVIVAL);
    }

    /** A player standing on the floor at the test-relative {@code pos}, looking north. */
    static ClickingPlayer at(GameTestHelper helper, BlockPos pos) {
        ClickingPlayer player = new ClickingPlayer(helper.getLevel());
        Vec3 feet = Vec3.atBottomCenterOf(helper.absolutePos(pos));
        player.moveTo(feet.x, feet.y, feet.z, 180.0F, 30.0F);
        return player;
    }

    /** The translation keys of what this player was told, in order. */
    List<String> toldKeys() {
        List<String> keys = new ArrayList<>(told.size());
        for (ClientboundSystemChatPacket line : told)
            keys.add(line.content().getContents() instanceof TranslatableContents translatable
                    ? translatable.getKey() : "<literal> " + line.content().getString());
        return keys;
    }

    /** The connection of a {@link ClickingPlayer}: it keeps the chat lines and lets everything else go. */
    private static final class Keeping extends ServerGamePacketListenerImpl {
        private final ClickingPlayer owner;

        private Keeping(MinecraftServer server, ClickingPlayer owner) {
            super(server, new Connection(PacketFlow.SERVERBOUND), owner,
                    CommonListenerCookie.createInitial(owner.getGameProfile(), false));
            this.owner = owner;
        }

        @Override
        public void send(Packet<?> packet) {
            send(packet, null);
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundSystemChatPacket line)
                owner.told.add(line);
        }
    }
}
