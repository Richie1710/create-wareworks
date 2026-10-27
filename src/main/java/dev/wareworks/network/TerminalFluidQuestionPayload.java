package dev.wareworks.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.Wareworks;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.ContainerPlan;
import dev.wareworks.core.terminal.FluidQuestion;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: what a click on a fluid cell would mean, asked before anything is promised (M31, issue #21,
 * ADR-073): the answer to a {@link TerminalFluidRequestPayload} whose acknowledgement did not name exactly this
 * question. <b>Nothing was requested.</b> The screen shows it and, on Fetch, sends the same request again with
 * {@link FluidQuestion#acknowledgement()}, which the server measures once more before it acts.
 * <p>
 * The numbers are the server's: a screen knows neither which empty containers the racks hold and are promised, nor
 * what the open requests owe of the fluid, nor the aisle's production patterns. Reading is bounded at
 * {@value ContainerPlan#MAX_PORTIONS} portions and reserve lines, whatever the length prefixes claim; the shortfall
 * travels by name, so one a build does not know reads as {@code NONE} rather than as a shifted one.
 *
 * @param containerId the menu the question belongs to; the screen ignores one for another menu
 * @param question    what the request would mean
 * @param door        the package door the click was for; empty for here. A Fetch sends exactly this door back
 */
public record TerminalFluidQuestionPayload(int containerId, FluidQuestion<ItemKey, FluidKey> question,
                                           Optional<BlockPos> door) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TerminalFluidQuestionPayload> TYPE =
            new CustomPacketPayload.Type<>(Wareworks.asResource("terminal_fluid_question"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalFluidQuestionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(TerminalFluidQuestionPayload::write, TerminalFluidQuestionPayload::new);

    /** Longest shortfall name a payload may claim. */
    private static final int MAX_NAME = 64;

    public TerminalFluidQuestionPayload {
        Objects.requireNonNull(question, "question");
        door = door == null ? Optional.empty() : door.map(BlockPos::immutable);
    }

    private TerminalFluidQuestionPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), readQuestion(buffer), buffer.readOptional(in -> in.readBlockPos()));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        writeQuestion(buffer, question);
        buffer.writeOptional(door, (out, pos) -> out.writeBlockPos(pos));
    }

    static void writeQuestion(RegistryFriendlyByteBuf buffer, FluidQuestion<ItemKey, FluidKey> question) {
        FluidKey.STREAM_CODEC.encode(buffer, question.fluid());
        buffer.writeVarLong(question.askedMb());
        buffer.writeVarLong(question.servedMb());
        buffer.writeVarInt(question.portions().size());
        for (ContainerPlan.Portion<ItemKey> portion : question.portions()) {
            ItemKey.STREAM_CODEC.encode(buffer, portion.container());
            ItemKey.STREAM_CODEC.encode(buffer, portion.filled());
            buffer.writeVarInt(portion.capacityMb());
            buffer.writeVarInt(portion.count());
        }
        buffer.writeBoolean(question.make().isPresent());
        question.make().ifPresent(offer -> {
            ItemKey.STREAM_CODEC.encode(buffer, offer.container());
            ItemKey.STREAM_CODEC.encode(buffer, offer.filled());
            buffer.writeVarInt(offer.capacityMb());
            buffer.writeVarInt(offer.count());
        });
        buffer.writeVarInt(question.trips());
        buffer.writeVarInt(question.perTrip());
        buffer.writeUtf(question.shortfall().name(), MAX_NAME);
        buffer.writeVarLong(question.fluidAvailableMb());
        buffer.writeVarLong(question.containersInStock());
        buffer.writeVarInt(question.smallestStep());
        buffer.writeVarInt(question.stationSlots());
        buffer.writeVarLong(question.fluidFromReserve());
        buffer.writeVarLong(question.fluidReserved());
        buffer.writeVarInt(question.emptiesFromReserve().size());
        for (FluidQuestion.ReservedEmpties<ItemKey> reserve : question.emptiesFromReserve()) {
            ItemKey.STREAM_CODEC.encode(buffer, reserve.container());
            buffer.writeVarLong(reserve.fromReserve());
            buffer.writeVarLong(reserve.reserved());
        }
    }

    static FluidQuestion<ItemKey, FluidKey> readQuestion(RegistryFriendlyByteBuf buffer) {
        FluidKey fluid = FluidKey.STREAM_CODEC.decode(buffer);
        long asked = buffer.readVarLong();
        long served = buffer.readVarLong();
        int portionCount = Math.min(Math.max(0, buffer.readVarInt()), ContainerPlan.MAX_PORTIONS);
        List<ContainerPlan.Portion<ItemKey>> portions = new ArrayList<>(portionCount);
        for (int i = 0; i < portionCount; i++)
            portions.add(new ContainerPlan.Portion<>(ItemKey.STREAM_CODEC.decode(buffer),
                    ItemKey.STREAM_CODEC.decode(buffer), Math.max(1, buffer.readVarInt()),
                    Math.max(1, buffer.readVarInt())));
        Optional<FluidQuestion.MakeOffer<ItemKey>> make = Optional.empty();
        if (buffer.readBoolean())
            make = Optional.of(new FluidQuestion.MakeOffer<>(ItemKey.STREAM_CODEC.decode(buffer),
                    ItemKey.STREAM_CODEC.decode(buffer), buffer.readVarInt(), Math.max(1, buffer.readVarInt())));
        int trips = buffer.readVarInt();
        int perTrip = buffer.readVarInt();
        ContainerPlan.Shortfall shortfall = shortfall(buffer.readUtf(MAX_NAME));
        long fluidAvailable = buffer.readVarLong();
        long containers = buffer.readVarLong();
        int smallest = buffer.readVarInt();
        int stationSlots = buffer.readVarInt();
        long fluidFromReserve = buffer.readVarLong();
        long fluidReserved = buffer.readVarLong();
        int reserveCount = Math.min(Math.max(0, buffer.readVarInt()), ContainerPlan.MAX_PORTIONS);
        List<FluidQuestion.ReservedEmpties<ItemKey>> reserves = new ArrayList<>(reserveCount);
        for (int i = 0; i < reserveCount; i++)
            reserves.add(new FluidQuestion.ReservedEmpties<>(ItemKey.STREAM_CODEC.decode(buffer),
                    buffer.readVarLong(), buffer.readVarLong()));
        return new FluidQuestion<>(fluid, asked, served, portions, make, trips, perTrip, shortfall, fluidAvailable,
                containers, smallest, stationSlots, fluidFromReserve, fluidReserved, reserves);
    }

    private static ContainerPlan.Shortfall shortfall(String name) {
        for (ContainerPlan.Shortfall shortfall : ContainerPlan.Shortfall.values()) {
            if (shortfall.name().equals(name))
                return shortfall;
        }
        return ContainerPlan.Shortfall.NONE;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
