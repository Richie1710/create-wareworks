package dev.wareworks.content.remote;

import java.util.Objects;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;

/**
 * The terminal a Warehouse Remote is linked to (issue #44, ADR-077): the value of the item component
 * {@code wareworks:remote_link}.
 * <p>
 * The position alone would let anyone aim a remote at any terminal — a creative client sends whole stacks with every
 * component it likes — so the link also names the terminal's own <b>identity</b>
 * ({@code WarehouseTerminalBlockEntity#terminalId()}), a random id the terminal keeps in its save. A remote opens only
 * the terminal that stands at {@link #terminal()} <b>and</b> has that id: a terminal broken and placed again, or
 * another player's terminal set down on the same spot, has another id, and the old link stops working.
 * <p>
 * Persistent and synced to clients in full: a creative client rebuilds the stack from its own copy when the player
 * moves it, so a client copy without the id would break the link.
 *
 * @param terminal   the terminal's dimension and block position
 * @param terminalId the terminal's identity when the remote was linked
 */
public record RemoteLink(GlobalPos terminal, UUID terminalId) {
    public static final Codec<RemoteLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GlobalPos.CODEC.fieldOf("terminal").forGetter(RemoteLink::terminal),
            UUIDUtil.CODEC.fieldOf("terminal_id").forGetter(RemoteLink::terminalId)).apply(instance, RemoteLink::new));

    public static final StreamCodec<ByteBuf, RemoteLink> STREAM_CODEC = StreamCodec.composite(
            GlobalPos.STREAM_CODEC, RemoteLink::terminal,
            UUIDUtil.STREAM_CODEC, RemoteLink::terminalId,
            RemoteLink::new);

    public RemoteLink {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(terminalId, "terminalId");
    }
}
