package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;

import dev.wareworks.core.port.PackagerSignAddress;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a Create <b>Packager</b> standing at a warehouse station means for that station (M26, issue #18): whether one is
 * there at all, what address its signs spell, and whether it has stopped listening to redstone.
 * <p>
 * <b>Wareworks adds no packing of its own.</b> Create's Packager is the only thing in the game that makes a package
 * (ADR-040), and the geometry alone decides the direction: a Packager's target inventory is the block its <b>back</b>
 * touches ({@code PackagerBlockEntity#addBehaviours:135-136} over
 * {@code CapManipulationBehaviourBase.InterfaceProvider#oppositeOfBlockFacing}, resolved in {@code findNewCapability}
 * to the position {@code packagerPos − FACING} queried from the face {@code FACING}). So a Packager behind a port can
 * only pack, because the port's capability is extract-only on every side, and one behind an input can only unpack. This
 * class is only the <b>reading</b> half of that: it moves nothing and changes nothing.
 * <p>
 * <b>Where it runs.</b> The goggle lines of a station are built on the client, and a client has every input this needs
 * — the Packager's block state, its block entity and the sign block entities around it — so naming the address costs
 * <b>no new sync at all</b> and nothing is read while nobody looks. Every lookup is guarded by {@code isLoaded}, so a
 * call on a server can never pull in a chunk (the hard rule against world searches).
 *
 * @see PackagerSignAddress for the sign rule itself, and for the ComputerCraft case it does not model
 */
public final class PackageHandover {
    private PackageHandover() {
    }

    /**
     * The Packager whose back touches the station at {@code stationPos}, i.e. the one that would pack what the station
     * holds (a port) or unpack into it (an input).
     * <p>
     * {@code FACING} must equal the direction <b>from</b> the station <b>to</b> the Packager: the Packager's target is
     * {@code packagerPos − FACING}, which is this station exactly then. A {@code RepackagerBlockEntity} is refused
     * although it is a {@code PackagerBlockEntity} ({@code RepackagerBlockEntity extends PackagerBlockEntity}): it only
     * ever re-boxes fragments of a Create network order and never packs loose items, so it would be the wrong thing to
     * describe — and it has no {@code LINKED} state to read either ({@code RepackagerBlock#createBlockStateDefinition}
     * adds only {@code FACING} and {@code POWERED}).
     * <p>
     * <b>At most one is answered</b>, the first in {@code Direction.values()} order. Create does allow two Packagers to
     * face away from the same inventory, and then both really do pack from it; describing one of them is still honest
     * (every line this feeds is about the Packager it names), and a second one is a build nobody makes on purpose.
     *
     * @return the Packager's position, or empty if no block around the station qualifies
     */
    public static Optional<BlockPos> packagerFor(Level level, BlockPos stationPos) {
        if (level == null)
            return Optional.empty();
        for (Direction side : Iterate.directions) {
            BlockPos pos = stationPos.relative(side);
            if (!level.isLoaded(pos))
                continue;
            BlockState state = level.getBlockState(pos);
            if (state.getOptionalValue(PackagerBlock.FACING).orElse(null) != side)
                continue;
            if (!(level.getBlockEntity(pos) instanceof PackagerBlockEntity packager)
                    || packager instanceof RepackagerBlockEntity)
                continue;
            return Optional.of(pos);
        }
        return Optional.empty();
    }

    /**
     * The address the <b>next redstone-driven</b> box this Packager sends would carry: the signs around it, read by
     * Create's own rule ({@link PackagerSignAddress}).
     * <p>
     * Re-read rather than taken from the public {@code signBasedAddress} field, which is only refreshed immediately
     * before a send and is therefore stale while the Packager stands idle — see the class javadoc of
     * {@link PackagerSignAddress}.
     * <p>
     * <b>Only the redstone channel.</b> Create applies the sign on one branch,
     * {@code if (!requestQueue && !signBasedAddress.isBlank())} ({@code PackagerBlockEntity:516-517}), which a
     * {@code LINKED} Packager never reaches: its redstone callers stop at {@code !redstoneModeActive()} and the
     * logistics network's caller always passes a request list, so that box carries the <b>order's</b> address. So
     * {@link #ignoresRedstone} must be asked <b>first</b> and this answer dropped while it is true — otherwise the
     * reading promises an address no box will get.
     *
     * @return the address, or {@link PackagerSignAddress#NONE} for an unaddressed package — never {@code null}
     */
    public static String addressAt(Level level, BlockPos packagerPos) {
        if (level == null)
            return PackagerSignAddress.NONE;
        List<String> neighbours = new ArrayList<>(Iterate.directions.length);
        for (Direction side : Iterate.directions)
            neighbours.add(signAddressAt(level, packagerPos.relative(side)));
        return PackagerSignAddress.lastOf(neighbours);
    }

    /**
     * Whether this Packager <b>ignores redstone</b>, which is the one failure of a package door that nothing else in
     * the game diagnoses: {@code PackagerBlockEntity#redstoneModeActive()} is {@code !LINKED} ({@code :309-312}), both
     * of its redstone callers check it ({@code lazyTick:290-291} and {@code activate:352-353}), and the block itself
     * says nothing whatsoever — so a single Stock Link stuck on the Packager stops the door for ever with nothing in
     * the game to read.
     * <p>
     * The state can lag the player's build by up to ten ticks, because {@code recheckIfLinksPresent} runs only in
     * {@code lazyTick} ({@code :285}) at Create's lazy rate of 10 ({@code SmartBlockEntity#setLazyTickRate}). It is
     * read anyway: the lag is a line that appears a moment late, while not reading it is a door that never opens.
     */
    public static boolean ignoresRedstone(Level level, BlockPos packagerPos) {
        if (level == null || !level.isLoaded(packagerPos))
            return false;
        return level.getBlockState(packagerPos).getOptionalValue(PackagerBlock.LINKED).orElse(false);
    }

    /**
     * The address one neighbouring block spells, or {@link PackagerSignAddress#NONE} if it is not a sign: the front
     * text if it says anything, otherwise the back ({@code PackagerBlockEntity#getSign:553-568}).
     * <p>
     * {@code getMessages(false)} is the unfiltered text, which is what Create reads — so a player with chat filtering
     * on still sees the address the box really gets.
     */
    private static String signAddressAt(Level level, BlockPos pos) {
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign))
            return PackagerSignAddress.NONE;
        return PackagerSignAddress.ofSign(lines(sign.getText(true)), lines(sign.getText(false)));
    }

    private static List<String> lines(SignText text) {
        Component[] messages = text.getMessages(false);
        List<String> lines = new ArrayList<>(messages.length);
        for (Component message : messages)
            lines.add(message.getString());
        return lines;
    }
}
