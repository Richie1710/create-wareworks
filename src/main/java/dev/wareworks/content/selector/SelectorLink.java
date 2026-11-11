package dev.wareworks.content.selector;

import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a Recipe Selector item is linked to (ADR-085, design §2): the value of the item component
 * {@code wareworks:selector_link}.
 * <p>
 * {@link #station()} is the Production Station a sneak-click linked it to; a local selector registers with it when it is
 * placed, and binds that station's return input on a sneak-click on a Warehouse Input. {@link #machine()} and
 * {@link #kind()} are the far tier's link to the factory's saw or basin (the Ender Recipe Selector); the local tier
 * leaves them empty. Positions only, never an identity: a selector re-validates everything it is linked to when it is
 * used, and a placed block keeps its own link, never the item's.
 * <p>
 * Persistent and synced to clients in full, like the Warehouse Remote's link: a creative client sends whole stacks
 * back to the server.
 *
 * @param station the production station's dimension and position, if linked
 * @param machine the far factory's machine, if linked (Ender tier)
 * @param kind    the kind of {@link #machine()}, if linked (Ender tier)
 */
public record SelectorLink(Optional<GlobalPos> station, Optional<GlobalPos> machine,
        Optional<SelectorMachine.Kind> kind) {
    /** No link at all; never stored on a stack (an empty link is removed instead). */
    public static final SelectorLink NONE = new SelectorLink(Optional.empty(), Optional.empty(), Optional.empty());

    private static final Codec<SelectorMachine.Kind> KIND_CODEC = Codec.STRING.comapFlatMap(
            name -> SelectorMachine.Kind.byName(name).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "unknown machine kind " + name)),
            SelectorMachine.Kind::name);

    private static final StreamCodec<ByteBuf, SelectorMachine.Kind> KIND_STREAM_CODEC = ByteBufCodecs.idMapper(
            id -> SelectorMachine.Kind.values()[Math.floorMod(id, SelectorMachine.Kind.values().length)],
            SelectorMachine.Kind::ordinal);

    public static final Codec<SelectorLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GlobalPos.CODEC.lenientOptionalFieldOf("station").forGetter(SelectorLink::station),
            GlobalPos.CODEC.lenientOptionalFieldOf("machine").forGetter(SelectorLink::machine),
            KIND_CODEC.lenientOptionalFieldOf("kind").forGetter(SelectorLink::kind)).apply(instance, SelectorLink::new));

    public static final StreamCodec<ByteBuf, SelectorLink> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), SelectorLink::station,
            ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), SelectorLink::machine,
            ByteBufCodecs.optional(KIND_STREAM_CODEC), SelectorLink::kind,
            SelectorLink::new);

    public SelectorLink {
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(kind, "kind");
    }

    /** This link with {@code station} as its station. */
    public SelectorLink withStation(GlobalPos station) {
        return new SelectorLink(Optional.of(station), machine, kind);
    }

    /** Whether nothing is linked. */
    public boolean isEmpty() {
        return station.isEmpty() && machine.isEmpty() && kind.isEmpty();
    }
}
