package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.core.terminal.ContainerPlan;
import dev.wareworks.core.terminal.FluidAcknowledgement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "please fetch {@code amountMb} of {@code fluid} into this terminal, filled into the warehouse's empty
 * containers" (M31, issue #21, ADR-073, {@code docs/warehouse-system.md} §3.9.6).
 * <p>
 * <b>Nothing in it is trusted</b>, exactly as in {@link TerminalRequestPayload}: the server resolves the menu the
 * sending player really has open, re-checks reach, clamps the amount to {@code maxTerminalFluidRequestBuckets}, checks
 * the door against its own warehouse and the fluid against its own fluid index, and measures the request again.
 * <p>
 * <b>The acknowledgement is consent, not permission.</b> A fluid request always asks first
 * ({@code core.terminal.FluidQuestion}); this field names the numbers of the question the player said yes to, and the
 * server carries the request out only if the question it measures now has exactly those numbers
 * ({@link FluidAcknowledgement#covers}). {@link FluidAcknowledgement#NONE} is a first click.
 *
 * @param containerId  the menu the player has open
 * @param fluid        the fluid the screen offered
 * @param amountMb     how much the player asked for, in millibuckets
 * @param door         the package door to deliver to; empty for here
 * @param acknowledged what the player accepted, {@link FluidAcknowledgement#NONE} for a first click
 */
public record TerminalFluidRequestPayload(int containerId, FluidKey fluid, long amountMb, Optional<BlockPos> door,
                                          FluidAcknowledgement acknowledged) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalFluidRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_fluid_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalFluidRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalFluidRequestPayload::write, TerminalFluidRequestPayload::new);

    public TerminalFluidRequestPayload {
        Objects.requireNonNull(fluid, "fluid");
        door = door == null ? Optional.empty() : door.map(BlockPos::immutable);
        if (acknowledged == null)
            acknowledged = FluidAcknowledgement.NONE;
    }

    private TerminalFluidRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), FluidKey.STREAM_CODEC.decode(buffer), buffer.readVarLong(),
                buffer.readOptional(in -> in.readBlockPos()), readAcknowledgement(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        FluidKey.STREAM_CODEC.encode(buffer, fluid);
        buffer.writeVarLong(amountMb);
        buffer.writeOptional(door, (out, pos) -> out.writeBlockPos(pos));
        writeAcknowledgement(buffer, acknowledged);
    }

    /** A first click is one zero byte; an answer is a one, the served amount, the portions and the made count. */
    static void writeAcknowledgement(RegistryFriendlyByteBuf buffer, FluidAcknowledgement acknowledged) {
        buffer.writeBoolean(acknowledged.given());
        if (!acknowledged.given())
            return;
        buffer.writeVarLong(acknowledged.servedMb());
        buffer.writeVarInt(acknowledged.portionCounts().size());
        for (int count : acknowledged.portionCounts())
            buffer.writeVarInt(count);
        buffer.writeVarInt(acknowledged.makeCount());
    }

    /** Reads what {@link #writeAcknowledgement} wrote, bounded at {@value ContainerPlan#MAX_PORTIONS} portions. */
    static FluidAcknowledgement readAcknowledgement(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean())
            return FluidAcknowledgement.NONE;
        long served = buffer.readVarLong();
        int portions = Math.min(Math.max(0, buffer.readVarInt()), ContainerPlan.MAX_PORTIONS);
        List<Integer> counts = new ArrayList<>(portions);
        for (int i = 0; i < portions; i++)
            counts.add(buffer.readVarInt());
        return new FluidAcknowledgement(true, served, counts, buffer.readVarInt());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
