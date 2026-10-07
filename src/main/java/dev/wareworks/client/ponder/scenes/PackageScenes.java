package dev.wareworks.client.ponder.scenes;

import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.redstone.DirectedDirectionalBlock;
import com.simibubi.create.content.redstone.smartObserver.SmartObserverBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.port.PortRedstone;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * The Ponder scene of a <b>package door</b> ({@code docs/warehouse-system.md} §3.2.5, M26, issue #18): a Create
 * Packager whose back touches a warehouse port packs what the warehouse hands over and writes the address off a plain
 * vanilla sign, and one whose back touches a warehouse input takes arriving packages apart.
 * <p>
 * <b>Why this scene exists at all.</b> Both directions already work with no Wareworks code in the item path — they are
 * two mods fitting together — and until this scene nothing in the game said so. There is no new block to find, no new
 * setting to discover and no recipe that hints at it, so a player can only learn the build by being shown it. That
 * makes this scene the feature's front door rather than its documentation.
 * <p>
 * <b>The three things a player cannot learn any other way,</b> and the beats that carry them:
 * <ul>
 * <li><b>the geometry is the direction</b> — the station the Packager's <b>back</b> touches decides whether the door
 * packs or unpacks, and it cannot be set wrong, because a port answers an extract-only view and an input an insert-only
 * one (pinned by the GameTests {@code packagerbehindaportcanneverunpack} and
 * {@code packagerbehindaninputcanneverpack});</li>
 * <li><b>the sign is the address</b> — a plain vanilla sign on the Packager, which is the string Create itself reads,
 * and the only address channel that exists without a logistics network;</li>
 * <li><b>what a door costs</b> — up to nine stacks in a box and about one box a second, so nobody reports a door as
 * slow that is running exactly as fast as Create's own Packager can.</li>
 * </ul>
 * <b>A Smart Observer, not a bare pulse</b> (the owner's decision for M26): a Smart Observer watching the port sustains
 * a signal while anything is extractable there, so it empties the door by itself, whereas one lever pulse gives one box
 * and then a 40-tick cooldown. The code path is identical either way — both reach {@code attemptToSend(null)} through
 * {@code redstoneModeActive()} — so this is purely which build is taught, and the one that keeps working unattended is
 * the one worth a scene. The simpler pulse is named in the documentation instead.
 * <p>
 * <b>One scene, not a beat added to an existing one</b> (ADR-016): the build is new — two Packagers, a sign, a Smart
 * Observer, a funnel — and fits neither existing schematic, and a new scene only adds new {@code text_n} keys, so no
 * shipped scene's keys move.
 * <p>
 * <b>What runs here and what is only drawn.</b> A {@code PonderLevel} is client-side, so the Packager's
 * {@code lazyTick} returns at its very first line and {@code neighborChanged} never fires: the redstone in this scene
 * is block states the storyboard sets, and the packing is the animation Create's own scenes drive through the block
 * entity's public fields ({@code heldBox}, {@code animationTicks}, {@code animationInward} — the same three
 * {@code PonderHilo.packagerCreate} writes). That is also what makes the scene safe: the one call the design pass
 * feared, {@code recheckIfLinksPresent}'s {@code setBlockAndUpdate}, sits <b>behind</b> that client-side guard and
 * cannot be reached from a scene at all.
 * <p>
 * As in {@link PortScenes}, the storyboard stays level-free: it also runs with {@code level == null} while the lang
 * generator collects its text keys, so nothing here reads a block or a block entity. The order of the
 * {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of the scene id.
 */
public final class PackageScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    /** A beat that only adds half a sentence to the one before it; long enough to read, short enough not to drag. */
    private static final int SHORT_IDLE = 60;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;
    /** Ticks between the redstone edge and the box appearing, so cause and effect are two readable moments. */
    private static final int SIGNAL_LEAD = 10;

    /** The face of a port on the {@link Side#RIGHT} plane the camera draws, with its predecessor left empty. */
    private static final Direction PORT_FRONT = Direction.WEST;
    /** The face of a block on the {@link Side#LEFT} inventory plane that points at the viewer. */
    private static final Direction LEFT_FRONT = Direction.NORTH;

    /** What the out door asks for per trip, and the number in the port's filter slot. */
    private static final int REQUEST_AMOUNT = 16;
    /** What the arriving package holds; a different item from the one that leaves, so the two halves never blur. */
    private static final int ARRIVING_AMOUNT = 24;

    /** The address a player writes on the sign at the out door. */
    private static final String OUT_ADDRESS = "Base North";
    /** The address the arriving box carries: another warehouse's, on purpose — an input opens every package. */
    private static final String IN_ADDRESS = "Quarry";

    /** NBT key of Create's {@code FilteringBehaviour} count, which is what the port's "Requested Amount" board sets. */
    private static final String FILTER_AMOUNT = "FilterAmount";
    /** Sign rotation that turns the written face towards the plate's fixed camera (north-west, 135°). */
    private static final int SIGN_FACING = 6;

    private PackageScenes() {
    }

    /**
     * Both doors in one story: the Packager behind a port packs the warehouse's goods and signs them, and the Packager
     * behind an input opens whatever arrives.
     */
    public static void packagesAtTheDoor(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_packages_at_the_door", "Packages at a Warehouse Door");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // <b>Both doors stand on the LEFT plane and the warehouse's racks on the RIGHT one.</b> The camera of a Ponder
        // plate is fixed (yRotation 145, xRotation −35), which puts the viewer over the low-X, low-Z corner: a block is
        // drawn in front of its neighbours when its X and its Z are the smaller ones. A Packager's own rule puts it one
        // step further out than the station it serves, so on the LEFT plane it stands in <b>front</b> of its station and
        // on the RIGHT plane <b>behind</b> it. The first draft of this scene used the RIGHT plane and the Packager — the
        // one block the scene is about — disappeared behind the port and the Smart Observer above it.
        //
        // The out door is at position 4, which lands near the middle of the screen with the position before it empty,
        // so the port's west face is free; the in door sits at position 1, and the warehouse's two racks face them
        // both from the near end of the other plane, which is the part of the plate those two leave empty.
        int outPort = 4;
        int inInput = 1;
        int outSource = 2;
        int inTarget = 3;

        BlockPos dock = aisle.dock(util);
        BlockPos port = aisle.rack(util, outPort, 0, Side.LEFT);
        BlockPos outPackager = aisle.inventory(util, outPort, 0, Side.LEFT);
        BlockPos observer = port.above();
        BlockPos wire = outPackager.above();
        // Away from the dock, so the sign is not between the two Packagers — a sign is read by every Packager it
        // touches, and one standing between them would look as though it addressed both.
        BlockPos sign = outPackager.relative(PonderAisle.AISLE);
        BlockPos input = aisle.rack(util, inInput, 0, Side.LEFT);
        BlockPos inPackager = aisle.inventory(util, inInput, 0, Side.LEFT);
        BlockPos target = aisle.rack(util, inTarget, 0, Side.RIGHT);

        ItemStack requested = new ItemStack(Items.IRON_INGOT);
        ItemStack outBox = addressedBox(OUT_ADDRESS, new ItemStack(Items.IRON_INGOT, REQUEST_AMOUNT));
        ItemStack inBox = addressedBox(IN_ADDRESS, new ItemStack(Items.COPPER_INGOT, ARRIVING_AMOUNT));

        // --- the stage ------------------------------------------------------------------------------------------------
        aisle.placeAisle(scene, util);
        // The warehouse itself: a two-wide rack wall on the far plane. The iron the out door sends comes out of one of
        // them and the copper that arrives goes into the other, so both trips cross the aisle and are seen.
        aisle.placeStorage(scene, util, inTarget, 0, Side.RIGHT);
        aisle.placeStorage(scene, util, outSource, 0, Side.RIGHT);
        aisle.placeOutput(scene, util, outPort, 0, Side.LEFT);
        aisle.placeInput(scene, util, inInput, 0, Side.LEFT);
        // The in door's visible connection to the rest of a factory: what a belt, a chute or a Frogport hands over.
        aisle.placeInsertingFunnelAbove(scene, inPackager);

        // Both Packagers face away from the station they serve, which is the whole rule of a package door: a
        // Packager's target inventory is the block at packagerPos - FACING ({@code PackageHandover}).
        scene.world().setBlock(outPackager, AllBlocks.PACKAGER.getDefaultState()
                .setValue(PackagerBlock.FACING, PonderAisle.outward(Side.LEFT)), false);
        scene.world().setBlock(inPackager, AllBlocks.PACKAGER.getDefaultState()
                .setValue(PackagerBlock.FACING, PonderAisle.outward(Side.LEFT)), false);
        // A Smart Observer looking straight down into the port, and one block of wire on to the Packager behind it.
        // One cannot touch both: two orthogonally adjacent blocks share no common neighbour, so a link is the minimum.
        scene.world().setBlock(observer, AllBlocks.SMART_OBSERVER.getDefaultState()
                .setValue(DirectedDirectionalBlock.TARGET, AttachFace.FLOOR)
                .setValue(DirectedDirectionalBlock.FACING, PonderAisle.AISLE), false);
        scene.world().setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), false);
        // A standing sign on the base plate beside the Packager. Nothing hangs in mid-air here, because a real world
        // pops such a sign off on the next neighbour update.
        //
        // ROTATION is the yaw its written face points along (0 south, 4 west, 8 north, 12 east, 22.5° apart), and the
        // plate's camera stands at a yaw of about -35°, so SIGN_FACING turns the writing towards the viewer. The
        // default of 0 shows a scene its blank back.
        scene.world().setBlock(sign, Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, SIGN_FACING), false);
        writeSign(scene, sign, OUT_ADDRESS);

        // The port really is configured the way the scene says: it asks for iron, by the stack, and unwired — so the
        // only redstone in this picture is the Smart Observer's, which is what the beats are about.
        scene.world().setFilterData(util.select().position(port), WarehouseOutputBlockEntity.class, requested);
        scene.world().modifyBlockEntityNBT(util.select().position(port), WarehouseOutputBlockEntity.class, nbt -> {
            nbt.putInt(FILTER_AMOUNT, REQUEST_AMOUNT);
            nbt.putString(RequestFilterBehaviour.REDSTONE_MODE_TAG, PortRedstone.UNLESS_POWERED.name());
        });
        // And so is the Smart Observer: it watches for the very item the port asks for.
        scene.world().setFilterData(util.select().position(observer), SmartObserverBlockEntity.class, requested);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection racks = util.select().fromTo(aisle.dockX() + outSource, PonderAisle.FLOOR_Y, aisle.aisleZ() + 1,
                aisle.dockX() + inTarget, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection outDoor = util.select().position(port).add(util.select().position(outPackager));
        Selection outSignal = util.select().position(observer).add(util.select().position(wire))
                .add(util.select().position(outPackager));
        Selection inDoor = util.select().position(input).add(util.select().position(inPackager))
                .add(util.select().position(inPackager.above()));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(racks, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(util.select().position(port), Direction.DOWN);
        scene.idle(FADE_IDLE);

        // --- 1. what the build is -------------------------------------------------------------------------------------
        // Fading in from the aisle side, so the Packager is seen arriving behind the port rather than just being there.
        scene.world().showSection(util.select().position(outPackager), Direction.NORTH);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Create Packager behind a Warehouse Output packs whatever the warehouse hands over")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(outPackager, PORT_FRONT));
        scene.idle(TEXT_IDLE);

        // --- 2. the geometry is the direction -------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.OUTPUT, "door", outDoor, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Its back has to touch the station, and which station that is decides the direction")
                .attachKeyFrame()
                .colored(PonderPalette.OUTPUT)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, PORT_FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(SHORT_IDLE)
                .text("A port only ever hands items out, so a Packager there can only pack: it cannot be set wrong")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, PORT_FRONT));
        scene.idle(SHORT_IDLE);

        // --- 3. the sign is the address -------------------------------------------------------------------------------
        scene.world().showSection(util.select().position(sign), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("A plain sign on the Packager is the address every box it sends will carry")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sign, LEFT_FRONT));
        scene.idle(TEXT_IDLE);

        // --- 4. when the door sends -----------------------------------------------------------------------------------
        scene.world().showSection(util.select().position(observer).add(util.select().position(wire)), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Smart Observer over the port powers the Packager while anything is waiting there")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(observer, PORT_FRONT));
        scene.idle(TEXT_IDLE);

        // --- the crane fills the door, and the box is made ------------------------------------------------------------
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        // Shown for TEXT_TICKS and not for the whole trip, the same correction the arrival beat below carries: this
        // caption used to be shown for 150 ticks longer than it is, while the beats that follow it idle only
        // SIGNAL_LEAD + CYCLE + SIGNAL_LEAD = 40, so it and the caption about what a box is worth were drawn at once
        // on two adjacent blocks for about 1.5 s. A caption must never outlive the idle that follows it
        // (RackBayScenes' class comment).
        scene.overlay().showText(TEXT_TICKS)
                .text("The crane brings what the port asked for, and the Packager boxes it up")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, PORT_FRONT));
        deliver(scene, crane, outSource, outPort, port);

        scene.world().toggleRedstonePower(outSignal);
        scene.effects().indicateRedstone(observer);
        scene.idle(SIGNAL_LEAD);
        packBox(scene, outPackager, outBox);
        scene.idle(PackagerBlockEntity.CYCLE + SIGNAL_LEAD);

        // --- 5. what one box is worth ---------------------------------------------------------------------------------
        scene.overlay().showText(TEXT_TICKS)
                .text("One box holds up to nine stacks, and a door sends about one box a second")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(outPackager, PORT_FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(SHORT_IDLE)
                .text("A funnel, a chute or a Frogport takes the box from the Packager and sends it on its way")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(outPackager, PORT_FRONT));
        scene.idle(SHORT_IDLE);
        // The box is gone: something behind the door took it, which is the next machine's story and not this scene's.
        clearBox(scene, outPackager);
        scene.world().toggleRedstonePower(outSignal);
        scene.idle(FADE_IDLE);

        // --- 6. the other door ----------------------------------------------------------------------------------------
        scene.world().showSection(inDoor, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.INPUT, "indoor", inDoor, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The same Packager at a Warehouse Input is an in door: it takes arriving packages apart")
                .attachKeyFrame()
                .colored(PonderPalette.INPUT)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(inPackager, LEFT_FRONT));
        // Inside that beat's own text, so the box is seen going in while the sentence that names it is still up.
        scene.idle(SIGNAL_LEAD);
        // A funnel standing on a block has no flap to animate (PonderAisle), so the arrival needs a cue of its own.
        scene.effects().indicateSuccess(inPackager);
        unpackBox(scene, inPackager, inBox);
        scene.idle(PackagerBlockEntity.CYCLE);
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.COPPER_INGOT, ARRIVING_AMOUNT));
        scene.effects().indicateSuccess(input);
        scene.idle(TEXT_IDLE - SIGNAL_LEAD - PackagerBlockEntity.CYCLE);
        // TEXT_TICKS, and not a guess at the length of the trip under it: a longer lifetime left this sentence on
        // screen while the red beat below was already drawn at the same anchor - glyph on glyph, both unreadable. The
        // idle after the trip keeps the two apart whatever store() costs.
        scene.overlay().showText(TEXT_TICKS)
                .text("Whatever address it carries: the warehouse keeps what was inside and the box is gone")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, PORT_FRONT));
        store(scene, crane, inInput, inTarget, target);
        scene.idle(TEXT_IDLE);

        // --- 7. the one way it stops ----------------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.RED, "whole", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A package is opened whole or not at all, so the input needs room for every stack in it")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, PORT_FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(SHORT_IDLE)
                .text("Engineer's Goggles on either door say what it is doing, and why a package was refused")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, PORT_FRONT));
        scene.idle(SHORT_IDLE);

        scene.markAsFinished();
    }

    // --- the two crane trips -----------------------------------------------------------------------------------------

    /** The crane takes the requested iron out of a rack on the far plane and drops it into the port. */
    private static void deliver(CreateSceneBuilder scene, CraneScript crane, int sourcePosition, int portPosition,
            BlockPos port) {
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(sourcePosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.IRON_INGOT, REQUEST_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(portPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(portPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(port);
        crane.moveTo(CranePose.at(portPosition, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.RIGHT), CranePhase.IDLE);
        scene.idle(10);
    }

    /** The crane takes what the box held out of the input and stores it in a rack on the other plane. */
    private static void store(CreateSceneBuilder scene, CraneScript crane, int inputPosition, int targetPosition,
            BlockPos target) {
        crane.moveTo(CranePose.at(inputPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.COPPER_INGOT, ARRIVING_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(targetPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(targetPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(target);
        crane.moveTo(CranePose.at(targetPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.RIGHT), CranePhase.IDLE);
        scene.idle(10);
    }

    // --- the Packager's animation ------------------------------------------------------------------------------------

    /**
     * Runs the Packager's outward animation with {@code box} in its tray — the three public fields Create's own
     * {@code PonderHilo.packagerCreate} writes, which is the only way a scene can show packing at all: a
     * {@code PonderLevel} is client-side, so {@code activate()} is never called and no redstone a scene sets ever
     * reaches the block entity.
     */
    private static void packBox(CreateSceneBuilder scene, BlockPos packager, ItemStack box) {
        scene.world().modifyBlockEntity(packager, PackagerBlockEntity.class, be -> {
            be.animationTicks = PackagerBlockEntity.CYCLE;
            be.animationInward = false;
            be.heldBox = box.copy();
        });
    }

    /** Runs the Packager's inward animation with {@code box} going in, i.e. an arriving package being opened. */
    private static void unpackBox(CreateSceneBuilder scene, BlockPos packager, ItemStack box) {
        scene.world().modifyBlockEntity(packager, PackagerBlockEntity.class, be -> {
            be.animationTicks = PackagerBlockEntity.CYCLE;
            be.animationInward = true;
            be.previouslyUnwrapped = box.copy();
        });
    }

    /** Empties the tray again, for the beat where something behind the door has taken the box away. */
    private static void clearBox(CreateSceneBuilder scene, BlockPos packager) {
        scene.world().modifyBlockEntity(packager, PackagerBlockEntity.class, be -> be.heldBox = ItemStack.EMPTY);
    }

    // --- building ----------------------------------------------------------------------------------------------------

    /**
     * A deterministic box holding {@code contents} and addressed to {@code address}, built from the data components
     * rather than through {@code PackageItem.containing}, whose {@code getRandomBox} would give the scene a different
     * box style on every run.
     */
    private static ItemStack addressedBox(String address, ItemStack... contents) {
        ItemStack box = PackageStyles.getDefaultBox();
        box.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(List.of(contents)));
        PackageItem.addAddress(box, address);
        return box;
    }

    /**
     * Writes {@code address} on the front of the sign, so the string a player reads in the world is the one the box
     * really gets ({@code PackagerSignAddress}: the front text wins over the back).
     * <p>
     * {@code setText} ends in {@code SignBlockEntity#markUpdated}, whose {@code sendBlockUpdated} and
     * {@code updateNeighbourForOutputSignal} are both no-ops on the wrapped level a scene runs in.
     */
    private static void writeSign(CreateSceneBuilder scene, BlockPos pos, String address) {
        scene.world().modifyBlockEntity(pos, SignBlockEntity.class,
                sign -> sign.setText(new SignText().setMessage(0, Component.literal(address)), true));
    }
}
